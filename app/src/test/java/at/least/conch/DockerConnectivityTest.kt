package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Connection *shapes* against real OpenSSH that the MINA harness only
 * approximates: ProxyJump through a real forwarding sshd into the
 * container's own inner sshd, the SOCKS5 proxy bridging real direct-tcpip
 * channels, and keyboard-interactive (PAM) authentication — the wire shape
 * of every 2FA / "Password:"-prompt server, which the app must satisfy
 * through its plain password path.
 *
 *   127.0.0.1:2235  forwarding allowed (jump host, SOCKS)
 *   127.0.0.1:2233  forwarding DENIED  (jump attempt must fail cleanly)
 *   127.0.0.1:2236  keyboard-interactive only
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerConnectivityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    /** App-path jump: the saved jump host dials the target; both keys TOFU'd. */
    private fun jumpConnect(jumpPort: Int, targetUser: String = "bothuser"): SSHClient {
        val jumpHost = Host(hostname = "127.0.0.1", username = "pwuser", authType = Host.AUTH_PASSWORD)
            .apply { port = jumpPort }
        // the target is the container's INNER :2223, unreachable from the
        // host except through the jump's direct-tcpip
        val target = Host(
            hostname = "127.0.0.1",
            username = targetUser,
            authType = Host.AUTH_KEY,
            keyId = "keyA",
            jumpHostId = jumpHost.id,
        ).apply { port = DockerMatrix.CONTAINER_SSH_PORT }
        return SshConnectionFactory.connect(
            host = target,
            prompt = DockerMatrix.acceptPrompt,
            store = newStore(),
            keyProvider = { ssh, _ -> ssh.loadKeys(DockerMatrix.keyFile("keyA").absolutePath) },
            password = { h -> if (h === jumpHost) "conch-pw-1" else null },
            jumpHost = jumpHost,
        )
    }

    @Test(timeout = 60_000)
    fun `proxy jump reaches the inner sshd through a forwarding jump host`() {
        DockerMatrix.requireMatrix()
        jumpConnect(DockerMatrix.FORWARDING_PORT).use { ssh ->
            // the inner sshd runs as a different instance: its pid file proves
            // we landed on :2223, not on the jump's :2225
            val out = DockerMatrix.exec(ssh, "whoami; echo \$SSH_CONNECTION")
            assertTrue("not authenticated as bothuser: '$out'", out.startsWith("bothuser"))
            assertTrue(
                "SSH_CONNECTION should show the inner :2223 endpoint: '$out'",
                out.trim().endsWith(" ${DockerMatrix.CONTAINER_SSH_PORT}"),
            )
        }
    }

    @Test(timeout = 60_000)
    fun `proxy jump through a non-forwarding host fails without leaking the jump`() {
        DockerMatrix.requireMatrix()
        val e = runCatching { jumpConnect(DockerMatrix.PW_AND_KEY_PORT).use { } }.exceptionOrNull()
        assertTrue("expected the jump to be refused, got: $e", e != null)
        // the real sshd's refusal must read as the tunnel policy, not as a crash
        val msg = SshConnectionFactory.describeError(e as Exception)
        assertTrue(
            "unexpected error text: $msg",
            msg.contains("prohibited", true) || msg.contains("open failed", true) ||
                msg.contains("ConnectionException", true),
        )
        // the jump transport itself must not be left authenticated: a healthy
        // probe connection still works (no port/fd exhaustion from leaks)
        DockerMatrix.connect(newStore(), DockerMatrix.PW_AND_KEY_PORT, "pwuser", password = "conch-pw-1")
            .use { probe ->
                assertEquals("OK", DockerMatrix.exec(probe, "echo OK").trim())
            }
    }

    @Test(timeout = 60_000)
    fun `socks5 proxy bridges to a container service by ip and by domain name`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(newStore(), DockerMatrix.FORWARDING_PORT, "pwuser", password = "conch-pw-1")
            .use { ssh ->
                val proxy = SocksProxy(ssh)
                val port = proxy.start(0)
                try {
                    val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    // ATYP 1 (IPv4)
                    assertEquals(
                        "SSH-2.0",
                        bannerVia(socks, InetSocketAddress("127.0.0.1", DockerMatrix.CONTAINER_SSH_PORT))
                    )
                    // ATYP 3 (domain): the JDK sends unresolved names as-is, the
                    // container resolves "localhost" on its side
                    assertEquals(
                        "SSH-2.0",
                        bannerVia(
                            socks,
                            InetSocketAddress.createUnresolved("localhost", DockerMatrix.CONTAINER_FWD_PORT)
                        ),
                    )
                } finally {
                    proxy.stop()
                }
            }
    }

    @Test(timeout = 60_000)
    fun `socks5 connect to a closed container port is refused not hung`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(newStore(), DockerMatrix.FORWARDING_PORT, "pwuser", password = "conch-pw-1")
            .use { ssh ->
                val proxy = SocksProxy(ssh)
                val port = proxy.start(0)
                try {
                    val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    val e = runCatching {
                        Socket(socks).use { s ->
                            s.connect(InetSocketAddress("127.0.0.1", 1), 10_000)
                        }
                    }.exceptionOrNull()
                    assertTrue("expected a SOCKS failure, got: $e", e is java.net.SocketException)
                } finally {
                    proxy.stop()
                }
            }
    }

    @Test(timeout = 60_000)
    fun `keyboard-interactive only server accepts the stored password`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(newStore(), DockerMatrix.KBD_INTERACTIVE_PORT, "pwuser", password = "conch-pw-1")
            .use { ssh ->
                assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
                // and the server really did not offer "password"
                val methods = DockerMatrix.exec(
                    ssh,
                    "grep -c 'PasswordAuthentication no' /etc/ssh/sshd_config_kbd"
                ).trim()
                assertEquals("1", methods)
            }
    }

    @Test(timeout = 60_000)
    fun `keyboard-interactive only server refuses a wrong password as an auth failure`() {
        DockerMatrix.requireMatrix()
        val e = runCatching {
            DockerMatrix.connect(newStore(), DockerMatrix.KBD_INTERACTIVE_PORT, "pwuser", password = "wrong").use { }
        }.exceptionOrNull()
        assertTrue("expected auth failure, got: $e", e is UserAuthException)
        // the reconnector must treat it as terminal (no retry storm against PAM)
        assertTrue(SshSession.isTerminalFailure(SshConnectionFactory.describeError(e as Exception)))
    }

    private fun bannerVia(proxy: Proxy, target: InetSocketAddress): String =
        Socket(proxy).use { s ->
            s.connect(target, 10_000)
            s.soTimeout = 10_000
            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            assertTrue("unexpected banner via SOCKS: $line", line != null && line.startsWith("SSH-2.0-OpenSSH"))
            "SSH-2.0"
        }
}
