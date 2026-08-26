package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the real SessionReconnector (the orchestration TerminalActivity
 * wires up) against an in-process sshd: drop → backoff schedule → rebuild
 * → tmux re-attach, plus the stop race guarantees.
 */
class SessionReconnectorInteractionTest {

    private lateinit var dir: File
    private lateinit var store: KnownHostsStore
    private var executor: ScheduledExecutorService? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-reconnector").toFile()
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
        val received = ConcurrentLinkedQueue<ByteArray>()

        override fun onSessionConnected() {
            connected.incrementAndGet()
        }

        override fun onSessionData(data: ByteArray) {
            received.add(data)
        }

        override fun onReconnecting(attempt: Int, delayMs: Long, reason: String) {
            reconnecting.add(Triple(attempt, delayMs, reason))
        }

        override fun onSessionStopped(reason: String) {
            stopped.add(reason)
        }

        fun text() = received.joinToString("") { String(it) }
    }

    private fun newReconnector(
        host: Host,
        listener: RecordingListener,
        slowConnectMs: Long = 0,
    ): Pair<SessionReconnector, ScheduledExecutorService> {
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "reconnect-timer").apply { isDaemon = true }
        }
        executor = sched
        var pending: ScheduledFuture<*>? = null
        val reconnector = SessionReconnector(
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
                        if (slowConnectMs > 0) Thread.sleep(slowConnectMs)
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
        return reconnector to sched
    }

    private fun reservePort(): Int {
        val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val p = s.localPort
        s.close()
        return p
    }

    @Test(timeout = 60_000)
    fun `server drop schedules reconnect which reattaches tmux on the same port`() {
        val port = reservePort()
        val server1 = TestSshd(fixedPort = port).start()
        store.add("127.0.0.1", port, server1.hostPublicKey)
        val host = Host(hostname = "127.0.0.1", username = server1.user, authType = Host.AUTH_PASSWORD)
            .apply {
                this.port = port
                tmuxAutoAttach = true
            }

        val listener = RecordingListener()
        val (reconnector, _) = newReconnector(host, listener)
        val tmuxLine = "COLORTERM=truecolor tmux new -A -s conch\r"

        // initial connect + tmux attach + working shell
        reconnector.start()
        awaitTrue("initial connect never came up") { listener.connected.get() > 0 }
        val shell1 = server1.shells.first()
        shell1.awaitStarted()
        shell1.awaitReceived(tmuxLine.toByteArray().size)
        reconnector.write("hello\r".toByteArray())
        awaitTrue("no echo") { listener.text().contains("hello\r") }

        // network dies
        server1.stopForcibly()
        server1.close()
        awaitTrue("no reconnect was scheduled") { listener.reconnecting.isNotEmpty() }
        val (attempt, delayMs, _) = listener.reconnecting.first()
        assertEquals(1, attempt)
        assertEquals(1_000L, delayMs)

        // server comes back on the same port; the 1s timer rebuilds and
        // the tmux attach line goes out again
        val server2 = TestSshd(fixedPort = port).start()
        try {
            awaitTrue("reconnect never came up") { listener.connected.get() >= 2 }
            val shell2 = server2.shells.first()
            shell2.awaitStarted()
            shell2.awaitReceived(tmuxLine.toByteArray().size)
            assertEquals(tmuxLine, String(shell2.receivedBytes()))

            reconnector.write("after-restore\r".toByteArray())
            awaitTrue("restored shell not usable") {
                listener.text().contains("after-restore\r")
            }

            // user stop: exactly one stopped event, nothing scheduled after
            reconnector.stop("user closed")
            awaitTrue("stop never surfaced") { listener.stopped.isNotEmpty() }
            reconnector.stop("second stop must not re-deliver") // e.g. onDestroy after banner tap
            Thread.sleep(1_500) // longer than the 1s base backoff
            assertEquals(listOf("user closed"), listener.stopped.toList())
            assertEquals(2, listener.connected.get())
            assertEquals(1, listener.reconnecting.size)
        } finally {
            server2.close()
        }
    }

    @Test(timeout = 30_000)
    fun `stop during a slow connect forwards stopped once and drops late callbacks`() {
        val server = TestSshd().start()
        try {
            store.add("127.0.0.1", server.port, server.hostPublicKey)
            val host = Host(hostname = "127.0.0.1", username = server.user, authType = Host.AUTH_PASSWORD)
                .apply {
                    this.port = server.port
                    tmuxAutoAttach = false
                }

            val listener = RecordingListener()
            val (reconnector, _) = newReconnector(host, listener, slowConnectMs = 400)

            reconnector.start()
            Thread.sleep(100) // the connector is now sleeping mid-handshake
            reconnector.stop("cancelled")

            awaitTrue("stop never surfaced") { listener.stopped.isNotEmpty() }
            Thread.sleep(800) // past the slow connector waking up
            assertEquals(listOf("cancelled"), listener.stopped.toList())
            assertEquals("late onConnected must never surface", 0, listener.connected.get())
            assertTrue("nothing may be scheduled after stop", listener.reconnecting.isEmpty())

            // simulate the extreme race: onConnected posted just before stop
            // processed it — the userClosed guard must swallow it
            reconnector.onConnected()
            assertEquals(0, listener.connected.get())
            assertEquals(1, listener.stopped.size)
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
