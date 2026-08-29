package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Host-key and client-key algorithm coverage against real OpenSSH:
 *
 *   - :2240 offers ONLY an ECDSA host key, :2241 ONLY an RSA one — TOFU must
 *     pin whatever type the server has and reconnect promptless against it
 *     (an app that assumed ed25519 host keys would re-prompt or fail),
 *   - the key-only instance (:2234) accepts an RSA-3072 and an ECDSA-P256
 *     client key as well as ed25519 — the three algorithms the app can
 *     generate/import, each negotiated with a modern (SHA-2) signature,
 *   - :2242 is a legacy appliance (SHA-1 kex, CBC ciphers, ssh-rsa host
 *     key). Whether conch connects there is a deliberate policy signal:
 *     the test records the outcome rather than demanding one, and is skipped
 *     where the base OpenSSH refuses to offer those algorithms at all.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerHostKeyAlgoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    private fun pinAndReconnect(port: Int, expectedHostKeyType: String) {
        val store = newStore()
        // first connect: unknown host key, TOFU accepts and pins it
        DockerMatrix.connectPw(store, port).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
        val pinned = store.file.readLines().filter { it.isNotBlank() }
        assertEquals("exactly one host key should be pinned", 1, pinned.size)
        assertTrue(
            "pinned host key type is not $expectedHostKeyType: ${pinned.first()}",
            pinned.first().contains(expectedHostKeyType),
        )
        // reconnect like a background session: no prompt allowed. Success
        // proves the pinned type was re-offered and matched.
        DockerMatrix.connectPw(store, port, prompt = null).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
        assertEquals("no second host key added", pinned, store.file.readLines().filter { it.isNotBlank() })
    }

    @Test(timeout = 60_000)
    fun `ecdsa-only host key is pinned and reconnects promptless`() {
        DockerMatrix.requireMatrix()
        pinAndReconnect(DockerMatrix.ECDSA_HOST_PORT, "ecdsa-sha2-nistp256")
    }

    @Test(timeout = 60_000)
    fun `rsa-only host key is pinned and reconnects promptless`() {
        DockerMatrix.requireMatrix()
        pinAndReconnect(DockerMatrix.RSA_HOST_PORT, "ssh-rsa")
    }

    @Test(timeout = 60_000)
    fun `an rsa client key authenticates against real openssh`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.KEY_ONLY_PORT,
            "keyuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyRSA"),
        ).use { ssh ->
            assertEquals("keyuser", DockerMatrix.exec(ssh, "whoami").trim())
            // modern OpenSSH refuses SHA-1 (ssh-rsa) signatures by default, so a
            // successful login proves the app negotiated rsa-sha2-256/512
            val v = DockerMatrix.sshVersion(ssh)
            assertTrue("no OpenSSH version reported: $v", v.contains("OpenSSH_"))
        }
    }

    @Test(timeout = 60_000)
    fun `an ecdsa client key authenticates against real openssh`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.KEY_ONLY_PORT,
            "keyuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyECDSA"),
        ).use { ssh ->
            assertEquals("keyuser", DockerMatrix.exec(ssh, "whoami").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `legacy SHA-1 appliance connection outcome is recorded`() {
        // The legacy instance does not start on bases whose OpenSSH dropped
        // SHA-1/CBC entirely (trixie/OpenSSH 10) — skip rather than fail there.
        DockerMatrix.requireOptionalInstance(
            DockerMatrix.LEGACY_PORT,
            "OpenSSH 10 refuses to offer SHA-1 kex / CBC ciphers",
        )
        val e = runCatching {
            DockerMatrix.connectPw(newStore(), DockerMatrix.LEGACY_PORT).use { ssh ->
                assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
            }
        }.exceptionOrNull()
        // sshj's DefaultConfig DOES carry group14-sha1 + CBC + ssh-rsa, so the
        // app is expected to reach this old box. If a future sshj upgrade drops
        // them the login will fail with a negotiation error — an intentional
        // signal, surfaced here rather than silently.
        if (e == null) {
            println("[legacy] conch connected to the SHA-1/CBC appliance (sshj still offers those algorithms)")
        } else {
            println("[legacy] conch refused the SHA-1/CBC appliance: ${e.message}")
            assertTrue(
                "a legacy-refusal must be an algorithm negotiation failure, not something else: $e",
                (e.message ?: "").contains("negotiat", true) ||
                    e is net.schmizz.sshj.transport.TransportException,
            )
        }
    }
}
