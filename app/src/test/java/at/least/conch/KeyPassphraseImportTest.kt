package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Passphrase-protected key import: the sniff that flips the UI into the
 * passphrase prompt (KeyManager.looksEncrypted) and the sshj load path
 * import() takes with a char[] passphrase. Parity driver: ConnectBot
 * "wrong key passphrase gives no retry".
 *
 * Fixture: resources/encrypted_ed25519_openssh.key — a throwaway key
 * protected with passphrase "conch-test-pass" (checked in; safe by design).
 */
class KeyPassphraseImportTest {

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/encrypted_ed25519_openssh.key")!!.readBytes()

    @Test
    fun `sniff flags encrypted openssh v1 key`() {
        assertTrue(KeyManager.looksEncrypted(fixture()))
    }

    @Test
    fun `sniff passes unencrypted openssh v1 key`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val plain = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "plain").toByteArray()
        assertFalse(KeyManager.looksEncrypted(plain))
    }

    @Test
    fun `sniff flags pkcs8-encrypted and legacy pem markers`() {
        assertTrue(
            KeyManager.looksEncrypted("-----BEGIN ENCRYPTED PRIVATE KEY-----\nabc".toByteArray())
        )
        assertTrue(
            KeyManager.looksEncrypted(
                "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC".toByteArray()
            )
        )
    }

    @Test
    fun `sniff ignores short or non-key bytes`() {
        assertFalse(KeyManager.looksEncrypted(ByteArray(0)))
        assertFalse(KeyManager.looksEncrypted("hello world, not a key".toByteArray()))
    }

    /** Mirrors KeyManager.import() with a passphrase: parse must succeed. */
    @Test
    fun `sshj loads encrypted fixture with the right passphrase`() {
        val tmp = File.createTempFile("conchenc", ".key")
        try {
            tmp.writeBytes(fixture())
            val provider = SSHClient().loadKeys(tmp.absolutePath, "conch-test-pass".toCharArray())
            checkNotNull(provider.private) // force the lazy parse
            assertEquals("ssh-ed25519", provider.type.toString())
        } finally {
            tmp.delete()
        }
    }

    /** A wrong passphrase must throw so the UI can re-prompt. */
    @Test
    fun `sshj rejects encrypted fixture with a wrong passphrase`() {
        val tmp = File.createTempFile("conchenc", ".key")
        try {
            tmp.writeBytes(fixture())
            var threw = false
            try {
                val p = SSHClient().loadKeys(tmp.absolutePath, "not-the-passphrase".toCharArray())
                p.private // force the lazy parse
            } catch (_: Exception) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            tmp.delete()
        }
    }

    /** No passphrase on an encrypted key must also throw (belt-and-braces). */
    @Test
    fun `sshj rejects encrypted fixture without a passphrase`() {
        val tmp = File.createTempFile("conchenc", ".key")
        try {
            tmp.writeBytes(fixture())
            var threw = false
            try {
                val p = SSHClient().loadKeys(tmp.absolutePath)
                p.private // force the lazy parse
            } catch (_: Exception) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            tmp.delete()
        }
    }
}
