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
 * The real [SessionReconnector] + [SshSession] against real OpenSSH under
 * three real outages, each produced with the docker CLI on the matrix
 * container:
 *
 *  - the server kills the session process (sshd child SIGKILL) — the link
 *    closes cleanly from the client's view
 *  - the whole sshd host restarts (`docker restart`) — connections reset,
 *    the server comes back with the SAME host keys, and the promptless
 *    background reconnect must succeed against the pinned key
 *  - the network silently vanishes (`docker pause` freezes the peer; no
 *    FIN, no RST) — only the request/response keep-alive can notice, so this
 *    pins that the 3 × 15 s keep-alive really declares the transport dead
 *    and that the retry loop lands once the peer is back (`docker unpause`)
 *
 * MINA cannot fake the last two. The pause case takes ~1–2 minutes by
 * design (keep-alive cadence is a wire contract).
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerReconnectTest {

    private lateinit var dir: File
    private lateinit var store: KnownHostsStore
    private var executor: ScheduledExecutorService? = null
    private var reconnector: SessionReconnector? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-docker-reconnect").toFile()
        store = KnownHostsStore(dir)
    }

    @After
    fun tearDown() {
        runCatching { reconnector?.stop("teardown") }
        executor?.shutdownNow()
        dir.deleteRecursively()
        // never leave the shared matrix frozen for the next test class
        DockerMatrix.docker("unpause", DockerMatrix.CONTAINER_NAME, allowFailure = true)
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

    private fun matrixHost() = DockerMatrix.pwHost { tmuxAutoAttach = false }

    private fun start(listener: RecordingListener): SessionReconnector {
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "reconnect-timer").apply { isDaemon = true }
        }
        executor = sched
        var pending: ScheduledFuture<*>? = null
        val host = matrixHost()
        val r = SessionReconnector(
            newSession = { cb ->
                SshSession(
                    context = null,
                    host = host,
                    initialCols = 80,
                    initialRows = 24,
                    callbacks = cb,
                    tofuPrompt = null,
                    post = { it.run() },
                    // exactly the production connector shape: TOFU store, no
                    // prompt (background reconnect), stored password
                    connector = { h, _ -> DockerMatrix.connect(store, h, prompt = null) },
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
        reconnector = r
        r.start()
        return r
    }

    private fun shellEchoes(r: SessionReconnector, listener: RecordingListener, marker: String) {
        r.write("echo $marker'DONE'\r".toByteArray())
        awaitTrue("shell did not echo $marker", 20_000) { listener.text().contains("${marker}DONE") }
    }

    @Test(timeout = 90_000)
    fun `server-side session kill reconnects with backoff and a working shell`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.pinHostKey(store)
        val listener = RecordingListener()
        val r = start(listener)
        awaitTrue("initial connect never came up", 30_000) { listener.connected.get() > 0 }
        shellEchoes(r, listener, "before-kill")

        // the server drops THIS user's session processes (admin kill / idle
        // reaper). Kill by user, not by -f 'sshd: pwuser' — the latter also
        // matches the docker-exec shell running the pattern and SIGKILLs it.
        DockerMatrix.dockerExec("pkill -9 -u pwuser || true", allowFailure = true)

        awaitTrue("no reconnect was scheduled", 20_000) { listener.reconnecting.isNotEmpty() }
        val (attempt, delayMs, reason) = listener.reconnecting.first()
        assertEquals(1, attempt)
        assertEquals(1_000L, delayMs)
        assertTrue("kill must not read as a user exit: $reason", !SshSession.isTerminalFailure(reason))

        awaitTrue("reconnect never came up", 30_000) { listener.connected.get() >= 2 }
        shellEchoes(r, listener, "after-kill")

        r.stop("user closed")
        awaitTrue("stop never surfaced", 10_000) { listener.stopped.isNotEmpty() }
        assertEquals(listOf("user closed"), listener.stopped.toList())
    }

    @Test(timeout = 120_000)
    fun `sshd host restart reconnects promptless against the persisted host key`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.pinHostKey(store)
        val entriesBefore = store.file.readLines().filter { it.isNotBlank() }
        val listener = RecordingListener()
        val r = start(listener)
        awaitTrue("initial connect never came up", 30_000) { listener.connected.get() > 0 }
        shellEchoes(r, listener, "before-restart")

        DockerMatrix.docker("restart", "-t", "1", DockerMatrix.CONTAINER_NAME, timeoutMs = 60_000)
        try {
            awaitTrue("no reconnect was scheduled", 30_000) { listener.reconnecting.isNotEmpty() }
            // retries keep going while the container boots (refused/EOF are
            // not terminal), then land on the restarted sshd
            awaitTrue("reconnect after restart never came up", 60_000) { listener.connected.get() >= 2 }
            shellEchoes(r, listener, "after-restart")
            // the host key survived the restart: same entries, nothing re-added
            assertEquals(entriesBefore, store.file.readLines().filter { it.isNotBlank() })
        } finally {
            DockerMatrix.waitForSshd(DockerMatrix.PW_AND_KEY_PORT)
            DockerMatrix.waitForSshd(DockerMatrix.FORWARDING_PORT)
        }
    }

    @Test(timeout = 240_000)
    fun `frozen peer is detected by keep-alive and the session returns after unpause`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.pinHostKey(store)
        val listener = RecordingListener()
        val r = start(listener)
        awaitTrue("initial connect never came up", 30_000) { listener.connected.get() > 0 }
        shellEchoes(r, listener, "before-pause")

        val frozenAt = System.currentTimeMillis()
        DockerMatrix.docker("pause", DockerMatrix.CONTAINER_NAME)
        try {
            // 3 unanswered keep-alives × 15 s = 45 s; allow scheduling slack
            val budget = SshConnectionFactory.KEEP_ALIVE_INTERVAL_SECONDS *
                (SshConnectionFactory.KEEP_ALIVE_MAX_UNANSWERED + 4) * 1_000L
            awaitTrue("keep-alive never declared the frozen transport dead", budget) {
                listener.reconnecting.isNotEmpty()
            }
            val detectMs = System.currentTimeMillis() - frozenAt
            val (_, _, reason) = listener.reconnecting.first()
            assertTrue("silent freeze must not read as a user exit: $reason", !SshSession.isTerminalFailure(reason))
            assertTrue(
                "detected too early ($detectMs ms) — keep-alive cadence is a wire contract",
                detectMs >= SshConnectionFactory.KEEP_ALIVE_INTERVAL_SECONDS * 1_000L,
            )
        } finally {
            DockerMatrix.docker("unpause", DockerMatrix.CONTAINER_NAME)
        }
        awaitTrue("reconnect after unpause never came up", 90_000) { listener.connected.get() >= 2 }
        shellEchoes(r, listener, "after-unpause")
    }
}
