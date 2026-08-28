package at.least.conch

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Two ways a TCP connection can "succeed" and still never yield an SSH
 * session — the shapes a captive portal, a half-open firewall or a wedged
 * server produce:
 *
 *   :2270 accepts the connection and never sends a byte,
 *   :2271 sends an SSH identification banner and then stalls forever.
 *
 * Neither can be reproduced by the in-process MINA server (which always
 * completes its handshake). The invariant pinned here is deterministic and
 * implementation-independent: a bounded connect attempt to such a peer must
 * NEVER produce a usable session. An external watchdog closes the client if
 * the attempt is still blocking, so the test never itself hangs — regardless
 * of whether sshj's own socket timeout happens to fire during key exchange.
 *
 * NOTE ON THE APP PATH: [SshConnectionFactory] sets `ssh.timeout = 0`
 * (unbounded socket reads) on purpose — the same socket timeout governs the
 * long-lived interactive reader loop, so a small value would kill idle
 * sessions. The app therefore has NO handshake-level deadline today; a
 * wedged peer leaves a session "connecting" until TCP itself gives up. These
 * tests stand as the executable spec a future app-side handshake watchdog
 * would satisfy. Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerHandshakeTimeoutTest {

    private val acceptAll = object : HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: java.security.PublicKey?) = true
        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
    }

    private fun boundedClient(): SSHClient = SSHClient(DefaultConfig()).apply {
        addHostKeyVerifier(acceptAll)
        connectTimeout = 6_000
        timeout = 6_000
    }

    /**
     * Attempts [ssh].connect on a worker thread and waits up to [budgetMs].
     * If the attempt is still blocking, the client is disconnected to unblock
     * it. Returns whether a usable (connected) session was ever produced.
     */
    private fun connectYieldsSession(ssh: SSHClient, port: Int, budgetMs: Long): Boolean {
        val done = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val err = AtomicReference<Throwable>()
        thread(isDaemon = true, name = "handshake-probe") {
            try {
                ssh.connect("127.0.0.1", port)
                connected.set(ssh.isConnected)
            } catch (t: Throwable) {
                err.set(t)
            } finally {
                done.countDown()
            }
        }
        val finished = done.await(budgetMs, TimeUnit.MILLISECONDS)
        // whether the attempt failed on its own or is still blocking, force
        // the transport down so nothing is left hanging for the next test
        runCatching { ssh.disconnect() }
        if (finished && err.get() != null) {
            // when it DID fail on its own, the app can render the reason
            val e = err.get() as? Exception ?: Exception(err.get())
            assertTrue(
                "describeError produced nothing useful for: ${err.get()}",
                SshConnectionFactory.describeError(e).isNotBlank(),
            )
        }
        return connected.get()
    }

    @Test(timeout = 30_000)
    fun `a port that accepts and never speaks never yields a session`() {
        DockerMatrix.requireMatrix()
        // sanity: the fixture really accepts TCP but sends nothing promptly
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", DockerMatrix.SILENT_ACCEPT_PORT), 3_000)
            s.soTimeout = 1_500
            val first = runCatching { s.getInputStream().read() }
            assertTrue(
                "silent-accept fixture unexpectedly sent data",
                first.isFailure || first.getOrNull() == -1,
            )
        }
        assertFalse(
            "a silent-accept peer must never produce a usable session",
            connectYieldsSession(boundedClient(), DockerMatrix.SILENT_ACCEPT_PORT, budgetMs = 12_000),
        )
    }

    @Test(timeout = 30_000)
    fun `a banner-then-stall server never yields a session`() {
        DockerMatrix.requireMatrix()
        assertFalse(
            "a banner-then-stall peer must never produce a usable session",
            connectYieldsSession(boundedClient(), DockerMatrix.BANNER_STALL_PORT, budgetMs = 12_000),
        )
    }
}
