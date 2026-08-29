package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * PuTTY v3 is on the shared support list (docs/parity.md "Private-key
 * formats"); this pins that sshj really parses puttygen 0.85's v3 files —
 * plain and Argon2-encrypted — and that they derive the same public key
 * as the OpenSSH original. Fixtures are the same files conch-ios tests
 * (ConchTests/Fixtures/Keys, passphrase `conch-test-pass`).
 */
class PuttyV3ImportTest {

    private fun res(name: String): ByteArray = javaClass.getResourceAsStream("/ppk/$name")!!.readBytes()

    private fun load(name: String, passphrase: String?): String {
        val tmp = File.createTempFile("conch", ".ppk")
        try {
            tmp.writeBytes(res(name))
            val provider = if (passphrase == null) {
                SSHClient().loadKeys(tmp.absolutePath)
            } else {
                SSHClient().loadKeys(tmp.absolutePath, passphrase.toCharArray())
            }
            return KeyManager.publicLineFor(provider.public, "x").substringBeforeLast(' ')
        } finally {
            tmp.delete()
        }
    }

    private fun expected(pub: String): String = String(res(pub)).trim().substringBeforeLast(' ')

    @Test
    fun `plain v3 files pass the gate and parse`() {
        for ((ppk, pub) in listOf("ed25519_v3.ppk" to "ed25519.pub", "ecdsa_p256_v3.ppk" to "ecdsa_p256.pub")) {
            KeyPolicy.rejectUnsupportedFormat(res(ppk))
            assertFalse(ppk, KeyManager.looksEncrypted(res(ppk)))
            assertEquals(ppk, expected(pub), load(ppk, null))
        }
    }

    @Test
    fun `argon2 encrypted v3 files prompt for a passphrase and parse with it`() {
        for ((ppk, pub) in listOf("ed25519_v3_enc.ppk" to "ed25519.pub", "ecdsa_p256_v3_enc.ppk" to "ecdsa_p256.pub")) {
            KeyPolicy.rejectUnsupportedFormat(res(ppk))
            assertTrue(ppk, KeyManager.looksEncrypted(res(ppk)))
            assertEquals(ppk, expected(pub), load(ppk, "conch-test-pass"))
        }
    }

    @Test
    fun `wrong passphrase on an argon2 v3 file is a passphrase failure not a parse failure`() {
        try {
            load("ed25519_v3_enc.ppk", "wrong")
            fail("expected a failure")
        } catch (e: Exception) {
            val m = (e.message ?: "").lowercase()
            assertTrue(e.toString(), "passphrase" in m || "password" in m || "mac" in m || "decrypt" in m)
        }
    }
}
