package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PuTTY v3 is on the shared support list (docs/parity.md "Private-key
 * formats"); this pins that sshj really parses puttygen 0.85's v3 files —
 * plain and Argon2-encrypted — and that they derive the same public key
 * as the OpenSSH original. Fixtures are the same files conch-ios tests
 * (ConchTests/Fixtures/Keys, passphrase `conch-test-pass`). Keys are fed
 * from memory, the way KeyManager.loadProvider does it.
 */
class PuttyV3ImportTest {

    private fun load(bytes: ByteArray, passphrase: String?): String {
        val provider = SSHClient().loadKeys(
            String(bytes, Charsets.ISO_8859_1),
            null,
            passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) },
        )
        return KeyManager.publicLineFor(provider.public, "x").substringBeforeLast(' ')
    }

    private fun expected(pub: String): String = String(testResource("/ppk/$pub")).trim().substringBeforeLast(' ')

    @Test
    fun `plain v3 files pass the gate and parse`() {
        for ((ppk, pub) in listOf("ed25519_v3.ppk" to "ed25519.pub", "ecdsa_p256_v3.ppk" to "ecdsa_p256.pub")) {
            val bytes = testResource("/ppk/$ppk")
            assertFalse(ppk, KeyPolicy.needsPassphrase(KeyPolicy.rejectUnsupportedFormat(bytes)))
            assertEquals(ppk, expected(pub), load(bytes, null))
        }
    }

    @Test
    fun `argon2 encrypted v3 files prompt for a passphrase and parse with it`() {
        for ((ppk, pub) in listOf("ed25519_v3_enc.ppk" to "ed25519.pub", "ecdsa_p256_v3_enc.ppk" to "ecdsa_p256.pub")) {
            val bytes = testResource("/ppk/$ppk")
            assertTrue(ppk, KeyPolicy.needsPassphrase(KeyPolicy.rejectUnsupportedFormat(bytes)))
            assertEquals(ppk, expected(pub), load(bytes, "conch-test-pass"))
        }
    }

    @Test
    fun `wrong passphrase on an argon2 v3 file is a passphrase failure not a parse failure`() {
        try {
            load(testResource("/ppk/ed25519_v3_enc.ppk"), "wrong")
            fail("expected a failure")
        } catch (e: Exception) {
            val m = (e.message ?: "").lowercase()
            assertTrue(e.toString(), "passphrase" in m || "password" in m || "mac" in m || "decrypt" in m)
        }
    }
}
