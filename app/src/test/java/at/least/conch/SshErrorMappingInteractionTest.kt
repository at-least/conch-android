package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * describeError() fed with failures from real connection attempts, so the
 * message mapping matches what sshj actually throws in the field.
 */
class SshErrorMappingInteractionTest {

    private fun freeClosedPort(): Int {
        val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = s.localPort
        s.close()
        return port
    }

    @Test(timeout = 30_000)
    fun `connection refused maps to refused message`() {
        val port = freeClosedPort()
        try {
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 5_000
            ssh.connect("127.0.0.1", port)
            ssh.disconnect()
            fail("expected connect to fail")
        } catch (e: Exception) {
            val msg = SshConnectionFactory.describeError(e)
            assertTrue("unexpected mapping: $msg (from ${e.javaClass.simpleName}: ${e.message})", msg.startsWith("Connection refused"))
        }
    }

    @Test(timeout = 30_000)
    fun `unresolvable host maps to resolve message`() {
        val host = "conch-definitely-not-real.invalid"
        // guard against wildcard-DNS environments where anything resolves
        try {
            InetAddress.getAllByName(host)
            Assume.assumeTrue("env resolves reserved .invalid names; skipping", false)
        } catch (_: java.net.UnknownHostException) {
            // expected
        }
        try {
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(host, 22)
            ssh.disconnect()
            fail("expected connect to fail")
        } catch (e: Exception) {
            val msg = SshConnectionFactory.describeError(e)
            assertTrue("unexpected mapping: $msg", msg == "Cannot resolve hostname")
        }
    }

    @Test(timeout = 30_000)
    fun `auth failure maps to authentication message`() {
        val server = TestSshd().start()
        try {
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect("127.0.0.1", server.port)
            try {
                ssh.authPassword(server.user, "definitely-wrong")
                fail("expected auth failure")
            } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
                val msg = SshConnectionFactory.describeError(e)
                assertTrue("unexpected mapping: $msg", msg.startsWith("Authentication failed"))
            } finally {
                ssh.disconnect()
            }
        } finally {
            server.close()
        }
    }

    @Test(timeout = 30_000)
    fun `host key rejection maps to a readable message`() {
        val server = TestSshd().start()
        try {
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(
                object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                    override fun verify(hostname: String?, port: Int, key: java.security.PublicKey?) = false

                    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
                },
            )
            try {
                ssh.connect("127.0.0.1", server.port)
                fail("expected handshake failure")
            } catch (e: Exception) {
                val msg = SshConnectionFactory.describeError(e)
                assertTrue(
                    "mapping should keep sshj's verify wording, got: [$msg]",
                    msg.contains("verify", ignoreCase = true),
                )
            } finally {
                ssh.disconnect()
            }
        } finally {
            server.close()
        }
    }
}
