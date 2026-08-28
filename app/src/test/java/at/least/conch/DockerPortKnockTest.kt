package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Port knocking against a real knock daemon (knockd in the matrix
 * container): the gated sshd on :2237 is only started — for 8 seconds —
 * after knockd sees the UDP sequence 2260,2261,2262 from this machine.
 * The unit tests prove datagrams leave the socket; only this proves a
 * knock daemon *recognises* them (payload, ordering, 150 ms gap) and that
 * the app's connect path knocks before dialing.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerPortKnockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gatedHost(knock: Boolean) = Host(
        hostname = "127.0.0.1",
        username = "pwuser",
        authType = Host.AUTH_PASSWORD,
        knockPorts = if (knock) DockerMatrix.KNOCK_PORTS else emptyList(),
    ).apply { port = DockerMatrix.GATED_PORT }

    private fun connectGated(knock: Boolean) = SshConnectionFactory.connect(
        host = gatedHost(knock),
        prompt = DockerMatrix.acceptPrompt,
        store = KnownHostsStore(tmp.newFolder()),
        keyProvider = { _, _ -> error("password auth in this test") },
        password = { "conch-pw-1" },
    )

    private fun gateClosed(): Boolean = !DockerMatrix.sshdAnswers(DockerMatrix.GATED_PORT)

    private fun awaitGateClosed() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !gateClosed()) Thread.sleep(250)
        assertTrue("gated sshd still listening — knockd cmd_timeout did not fire", gateClosed())
    }

    @Test(timeout = 90_000)
    fun `gated port is closed without a knock and opens after the app's knock sequence`() {
        DockerMatrix.requireMatrix()
        val knockd = DockerMatrix.dockerExec("pgrep -c knockd || true").trim()
        assertTrue("knockd is not running in the matrix container", knockd.toIntOrNull() ?: 0 > 0)
        awaitGateClosed()

        // no knock: nothing is listening (a Docker port proxy may accept and
        // then drop the TCP connection — either way no SSH handshake happens)
        val e = runCatching { connectGated(knock = false).use { } }.exceptionOrNull()
        assertTrue("connect without knocking must fail, got: $e", e != null)

        // the app's own pre-connect knock (SshConnectionFactory → PortKnocker)
        connectGated(knock = true).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
            val port = DockerMatrix.exec(ssh, "echo \$SSH_CONNECTION").trim().substringAfterLast(' ')
            assertEquals("2227", port)
        }
        val log = DockerMatrix.dockerExec("tail -20 /var/log/knockd.log")
        assertTrue("knockd never saw the full sequence:\n$log", log.contains("OPEN SESAME"))

        // the gate closes again after knockd's cmd_timeout
        awaitGateClosed()
    }

    @Test(timeout = 90_000)
    fun `knock sequence in the wrong order does not open the gate`() {
        DockerMatrix.requireMatrix()
        awaitGateClosed()
        val before = DockerMatrix.dockerExec("grep -c 'OPEN SESAME' /var/log/knockd.log || true").trim()
        PortKnocker.knock("127.0.0.1", DockerMatrix.KNOCK_PORTS.reversed())
        Thread.sleep(1_000)
        assertTrue("gate opened on a reversed sequence", gateClosed())
        val after = DockerMatrix.dockerExec("grep -c 'OPEN SESAME' /var/log/knockd.log || true").trim()
        assertEquals("knockd must not have fired", before, after)
    }
}
