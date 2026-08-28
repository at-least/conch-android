package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Connect-time failure modes surfaced as clean, mapped errors — and the full
 * path over IPv6, which every other matrix test skips (they all dial
 * 127.0.0.1). The keep-alive death + recovery of a *silent* peer is covered
 * by [DockerReconnectTest] (container pause), which is more deterministic
 * than an iptables blackhole through Docker's port proxy.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerNetworkFailureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    private fun host(hostname: String, port: Int) = Host(
        hostname = hostname,
        username = "pwuser",
        authType = Host.AUTH_PASSWORD,
    ).apply { this.port = port }

    private fun connectExpectingFailure(hostname: String, port: Int): Exception {
        val e = runCatching {
            SshConnectionFactory.connect(
                host = host(hostname, port),
                prompt = DockerMatrix.acceptPrompt,
                store = newStore(),
                keyProvider = { _, _ -> error("password auth in this test") },
                password = { "conch-pw-1" },
            ).use { }
        }.exceptionOrNull()
        assertTrue("expected the connect to fail", e is Exception)
        return e as Exception
    }

    @Test(timeout = 60_000)
    fun `the full connect auth and exec path works over IPv6 loopback`() {
        DockerMatrix.requireMatrix()
        val store = newStore()
        val h = host("::1", DockerMatrix.PW_AND_KEY_PORT)
        SshConnectionFactory.connect(
            host = h,
            prompt = DockerMatrix.acceptPrompt,
            store = store,
            keyProvider = { _, _ -> error("password auth in this test") },
            password = { "conch-pw-1" },
        ).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
        // TOFU recorded the v6 endpoint in OpenSSH's bracketed [::1]:port form
        val line = store.file.readLines().first { it.isNotBlank() }
        assertTrue("v6 host key not bracketed: $line", line.startsWith("[::1]:${DockerMatrix.PW_AND_KEY_PORT} "))
        // promptless reconnect matches the pinned v6 entry
        SshConnectionFactory.connect(
            host = h,
            prompt = null,
            store = store,
            keyProvider = { _, _ -> error("password auth in this test") },
            password = { "conch-pw-1" },
        ).close()
    }

    @Test(timeout = 30_000)
    fun `a closed port maps to a connection-refused error`() {
        DockerMatrix.requireMatrix()
        // 2269 sits between the alt-server ports and nothing binds it
        val e = connectExpectingFailure("127.0.0.1", 2269)
        val msg = SshConnectionFactory.describeError(e)
        assertTrue("expected a connection-refused message, got: '$msg' ($e)", msg.contains("refused", true))
    }

    @Test(timeout = 30_000)
    fun `an unresolvable hostname maps to a resolve error`() {
        DockerMatrix.requireMatrix()
        val e = connectExpectingFailure("conch-nonexistent-host.invalid", DockerMatrix.PW_AND_KEY_PORT)
        val msg = SshConnectionFactory.describeError(e)
        assertTrue("expected a resolve-failure message, got: '$msg' ($e)", msg.contains("resolve", true))
    }
}
