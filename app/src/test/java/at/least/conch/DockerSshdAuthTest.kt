package at.least.conch

import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Auth matrix against real OpenSSH in Docker — not the in-process MINA server,
 * so wire-level reality (locked accounts, auth-method filtering, host-key
 * negotiation) is exercised:
 *
 *   127.0.0.1:2233  password + pubkey   pwuser / conch-pw-1, bothuser / conch-pw-2 or keyA
 *   127.0.0.1:2234  pubkey only         keyuser with keyB, bothuser with keyA
 *   keyC is never installed: the "unknown client key" scenario
 *
 * Opt-in (needs Docker): start the matrix, then
 *   tools/sshd-matrix/run.sh
 *   ./gradlew testFossDebugUnitTest -Dconch.localSshdTest=true --tests '*.DockerSshdAuthTest'
 *
 * Wider real-OpenSSH coverage (SFTP, forwarding, PTY, tmux, ZMODEM) lives in
 * [DockerOpenSshIntegrationTest].
 */
class DockerSshdAuthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    @Test
    fun `password authenticates and execs on the password port`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connectPw(newStore(), DockerMatrix.PW_AND_KEY_PORT).use { ssh ->
            DockerMatrix.exec(ssh, "echo MATRIX_OK").let {
                assertEquals("MATRIX_OK", it.trim())
            }
        }
    }

    @Test
    fun `pubkey authenticates on the key-only port`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.KEY_ONLY_PORT,
            "keyuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyB"),
        ).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
    }

    @Test
    fun `pubkey authenticates on the password-plus-key port`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.PW_AND_KEY_PORT,
            "bothuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyA"),
        ).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
    }

    @Test
    fun `password is refused on the key-only port`() {
        DockerMatrix.requireMatrix()
        val e = runCatching {
            DockerMatrix.connect(
                newStore(),
                DockerMatrix.KEY_ONLY_PORT,
                "bothuser",
                password = "conch-pw-2",
            ).use { }
        }.exceptionOrNull()
        assertTrue("expected auth failure, got: $e", e is UserAuthException)
    }

    @Test
    fun `unknown client key is refused`() {
        DockerMatrix.requireMatrix()
        val e = runCatching {
            DockerMatrix.connect(
                newStore(),
                DockerMatrix.PW_AND_KEY_PORT,
                "bothuser",
                authType = Host.AUTH_KEY,
                keyFile = DockerMatrix.keyFile("keyC"),
            ).use { }
        }.exceptionOrNull()
        assertTrue("expected auth failure, got: $e", e is UserAuthException)
    }

    @Test
    fun `tofu-accepted host key allows promptless reconnect`() {
        DockerMatrix.requireMatrix()
        val store = newStore()
        // first connect: unknown host key, prompt accepts (TOFU add)
        DockerMatrix.connectPw(store, DockerMatrix.PW_AND_KEY_PORT).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
        // reconnect like a background session: no prompt allowed anymore
        DockerMatrix.connectPw(store, DockerMatrix.PW_AND_KEY_PORT, prompt = null).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
    }
}
