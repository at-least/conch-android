package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clean-exit semantics (competitor parity: ConnectBot "Mark session as
 * cleanly closed when exiting with CTRL+D"): a reader-loop EOF on a
 * session that lived >= SshSession.MIN_SESSION_MS surfaces as ONE
 * onSessionStopped and never schedules a reconnect; a short-lived clean
 * close keeps the reconnect loop (still covers flaky handshakes / LB FIN).
 */
class CleanExitInteractionTest {

    private lateinit var dir: File
    private lateinit var store: KnownHostsStore
    private var executor: ScheduledExecutorService? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-cleanexit").toFile()
        store = KnownHostsStore(dir)
    }

    @After
    fun tearDown() {
        executor?.shutdownNow()
        dir.deleteRecursively()
    }

    private class RecordingListener : SessionReconnector.Listener {
        val connected = AtomicInteger(0)
        val reconnecting = ConcurrentLinkedQueue<Triple<Int, Long, String>>()
        val stopped = ConcurrentLinkedQueue<String>()

        override fun onSessionConnected() {
            connected.incrementAndGet()
        }

        override fun onSessionData(data: ByteArray) {
            // not asserted here; the clean-exit path is reason-driven
        }

        override fun onReconnecting(attempt: Int, delayMs: Long, reason: String) {
            reconnecting.add(Triple(attempt, delayMs, reason))
        }

        override fun onSessionStopped(reason: String) {
            stopped.add(reason)
        }
    }

    private fun newReconnector(
        host: Host,
        listener: RecordingListener,
    ): SessionReconnector {
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cleanexit-timer").apply { isDaemon = true }
        }
        executor = sched
        var pending: ScheduledFuture<*>? = null
        return SessionReconnector(
            newSession = { cb ->
                SshSession(
                    context = null,
                    host = host,
                    initialCols = 80,
                    initialRows = 24,
                    callbacks = cb,
                    tofuPrompt = null,
                    post = { it.run() },
                    connector = { h, _ ->
                        SshConnectionFactory.connect(
                            host = h,
                            prompt = null,
                            store = store,
                            keyProvider = { _, _ -> throw IllegalStateException("no key") },
                            password = { "testpw" },
                        )
                    },
                )
            },
            listener = listener,
            postDelayed = { delayMs, action ->
                pending?.cancel(false)
                pending = sched.schedule(action, delayMs, TimeUnit.MILLISECONDS)
            },
            cancelScheduled = {
                pending?.cancel(false)
                pending = null
            },
        )
    }

    @Test(timeout = 90_000)
    fun `server-side exit after a lived-in session stops instead of reconnecting`() {
        val server = TestSshd().start()
        try {
            store.add("127.0.0.1", server.port, server.hostPublicKey)
            val host = Host(hostname = "127.0.0.1", username = server.user, authType = Host.AUTH_PASSWORD)
                .apply {
                    this.port = server.port
                    tmuxAutoAttach = false
                }

            val listener = RecordingListener()
            val reconnector = newReconnector(host, listener)

            reconnector.start()
            awaitTrue("initial connect never came up") { listener.connected.get() > 0 }
            val shell = server.shells.first()
            shell.awaitStarted()

            // the shell must outlive MIN_SESSION_MS for the clean-exit rule
            Thread.sleep(SshSession.MIN_SESSION_MS + 1_500)

            // user types `exit`: server closes the channel cleanly (EOF)
            shell.serverExit(0)

            awaitTrue("stopped never surfaced") { listener.stopped.isNotEmpty() }
            Thread.sleep(2_000) // longer than the 1s base backoff
            assertEquals(listOf(SshSession.REASON_SESSION_ENDED), listener.stopped.toList())
            assertEquals("no reconnect may be scheduled after a clean exit", 0, listener.reconnecting.size)
            assertEquals(1, listener.connected.get())
        } finally {
            server.close()
        }
    }

    @Test(timeout = 60_000)
    fun `short-lived clean close still reconnects`() {
        val server = TestSshd().start()
        try {
            store.add("127.0.0.1", server.port, server.hostPublicKey)
            val host = Host(hostname = "127.0.0.1", username = server.user, authType = Host.AUTH_PASSWORD)
                .apply {
                    this.port = server.port
                    tmuxAutoAttach = false
                }

            val listener = RecordingListener()
            val reconnector = newReconnector(host, listener)

            reconnector.start()
            awaitTrue("initial connect never came up") { listener.connected.get() > 0 }
            val shell = server.shells.first()
            shell.awaitStarted()

            // die immediately — under MIN_SESSION_MS, so NOT a session end
            shell.serverExit(0)

            awaitTrue("reconnect was not scheduled") { listener.reconnecting.isNotEmpty() }
            assertTrue("must not surface stopped for a short-lived close", listener.stopped.isEmpty())
            reconnector.stop("test done")
            awaitTrue("stop never surfaced") { listener.stopped.isNotEmpty() }
        } finally {
            server.close()
        }
    }
}

private fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    throw AssertionError(message)
}
