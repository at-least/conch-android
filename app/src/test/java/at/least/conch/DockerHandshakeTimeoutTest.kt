package at.least.conch

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Two ways a TCP connection can "succeed" and still never yield an SSH
 * session — the shapes a captive portal, a half-open firewall or a wedged
 * server produce:
 *
 *   :2270 accepts the connection and never sends a byte,
 *   :2271 sends an SSH identification banner and then stalls forever.
 *
 * Neither can be reproduced by the in-process MINA server (which always
 * completes its handshake). A client that sets a read timeout must surface
 * a clean, bounded failure — not hang, not crash, not corrupt state.
 *
 * NOTE ON THE APP PATH: [SshConnectionFactory] sets `ssh.timeout = 0`
 * (unbounded socket reads) on purpose — the same socket timeout governs the
 * long-lived interactive reader loop, so a small value would kill idle
 * sessions. The app therefore has NO handshake-level deadline today; a
 * silent peer leaves a session "connecting" until TCP itself gives up.
 * These tests pin the wire behaviour with an explicit bound and stand as the
 * executable spec a future app-side handshake watchdog would satisfy.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
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

    @Test(timeout = 30_000)
    fun `a port that accepts and never speaks fails within the read budget`() {
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
        val t0 = System.currentTimeMillis()
        val e = runCatching {
            boundedClient().use { it.connect("127.0.0.1", DockerMatrix.SILENT_ACCEPT_PORT) }
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("expected a bounded handshake failure, got: $e", e != null)
        assertTrue("did not fail within the 6s read budget (took ${elapsed}ms)", elapsed < 12_000)
        // the app's error mapper turns it into something a user can read
        assertTrue(
            "describeError produced nothing useful for: $e",
            SshConnectionFactory.describeError(e as Exception).isNotBlank(),
        )
    }

    @Test(timeout = 30_000)
    fun `a banner-then-stall server fails during key exchange within the read budget`() {
        DockerMatrix.requireMatrix()
        val t0 = System.currentTimeMillis()
        val e = runCatching {
            boundedClient().use { it.connect("127.0.0.1", DockerMatrix.BANNER_STALL_PORT) }
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - t0
        // the identification banner is read, then KEXINIT never arrives → the
        // read times out inside the transport, not the TCP connect
        assertTrue("expected a bounded kex failure, got: $e", e != null)
        assertTrue("did not fail within the 6s read budget (took ${elapsed}ms)", elapsed < 12_000)
        assertTrue(
            "describeError produced nothing useful for: $e",
            SshConnectionFactory.describeError(e as Exception).isNotBlank(),
        )
    }
}
