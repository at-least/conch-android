package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The shared private-key format policy (KeyPolicy.rejectUnsupportedFormat,
 * mirrored by iOS PrivateKeyCodec): a refused form throws the exact
 * conversion message before sshj parses anything and before the
 * passphrase prompt (looksEncrypted stays false for it); supported forms
 * pass the gate untouched.
 */
class KeyFormatPolicyTest {

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/$name")!!.readBytes()

    private fun rejected(bytes: ByteArray): String {
        try {
            KeyPolicy.rejectUnsupportedFormat(bytes)
        } catch (e: IllegalArgumentException) {
            return e.message!!
        }
        fail("expected the format policy to refuse this key")
        error("unreachable")
    }

    /** A minimal openssh-key-v1 blob carrying only magic + cipher + kdf names. */
    private fun opensshV1(cipher: String, kdf: String): ByteArray {
        fun str(s: String): ByteArray {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            return byteArrayOf(0, 0, 0, b.size.toByte()) + b
        }
        val bin = "openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1) + str(cipher) + str(kdf) + ByteArray(16)
        return (
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(bin) +
                "\n-----END OPENSSH PRIVATE KEY-----\n"
            ).toByteArray()
    }

    // ---------------------------------------------------------------- refused

    @Test
    fun `a real chacha20-poly1305 openssh key is refused with the re-encrypt command`() {
        val bytes = resource("encrypted_ed25519_chacha20.key")
        assertEquals(
            "This key is encrypted with chacha20-poly1305@openssh.com; " +
                "re-encrypt it with: ssh-keygen -p -Z aes256-ctr -f <key>",
            rejected(bytes),
        )
        assertFalse("must not reach the passphrase prompt", KeyManager.looksEncrypted(bytes))
    }

    @Test
    fun `cbc and 3des openssh ciphers are refused, naming the cipher`() {
        for (cipher in listOf("aes256-cbc", "aes128-ctr", "3des-cbc")) {
            assertEquals(KeyPolicy.cipherUnsupported(cipher), rejected(opensshV1(cipher, "bcrypt")))
        }
        assertTrue(KeyPolicy.cipherUnsupported("aes256-cbc").startsWith("This key is encrypted with aes256-cbc;"))
    }

    @Test
    fun `encrypted pkcs8 is refused`() {
        val bytes = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIIB...\n".toByteArray()
        assertEquals(KeyPolicy.PKCS8_ENCRYPTED_UNSUPPORTED, rejected(bytes))
        assertEquals(
            "Encrypted PKCS#8 keys are not supported; convert with: ssh-keygen -p -f <key>  " +
                "(or openssl pkey -in <key> -out plain.pem)",
            KeyPolicy.PKCS8_ENCRYPTED_UNSUPPORTED,
        )
        assertFalse(KeyManager.looksEncrypted(bytes))
    }

    @Test
    fun `legacy proc-type pem is refused`() {
        val bytes = "-----BEGIN EC PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,00\n".toByteArray()
        assertEquals(KeyPolicy.LEGACY_PEM_ENCRYPTED_UNSUPPORTED, rejected(bytes))
        assertFalse(KeyManager.looksEncrypted(bytes))
    }

    @Test
    fun `pkcs1 rsa pem is refused with the rsa policy message`() {
        assertEquals(KeyPolicy.RSA_NOT_FOR_LOGIN, rejected("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n".toByteArray()))
        // even when it is also legacy-encrypted, RSA is the reason the user sees
        assertEquals(
            KeyPolicy.RSA_NOT_FOR_LOGIN,
            rejected("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n".toByteArray()),
        )
    }

    @Test
    fun `putty v1 and v2 are refused, v3 passes`() {
        assertEquals(
            "PuTTY v2 keys are not supported; save it as v3 in puttygen " +
                "(or export as OpenSSH: puttygen -O private-openssh)",
            rejected("PuTTY-User-Key-File-2: ssh-ed25519\nEncryption: none\n".toByteArray()),
        )
        assertEquals(
            KeyPolicy.puttyVersionUnsupported(1),
            rejected("PuTTY-User-Key-File-1: ssh-ed25519\nEncryption: none\n".toByteArray()),
        )
        KeyPolicy.rejectUnsupportedFormat("PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes256-cbc\n".toByteArray())
    }

    // --------------------------------------------------------------- allowed

    @Test
    fun `bcrypt aes256-ctr openssh key passes the gate and still prompts for its passphrase`() {
        val bytes = resource("encrypted_ed25519_openssh.key")
        KeyPolicy.rejectUnsupportedFormat(bytes)
        val header = KeyPolicy.openSshV1Header(String(bytes, Charsets.ISO_8859_1))!!
        assertEquals(KeyPolicy.OpenSshV1Header("aes256-ctr", "bcrypt"), header)
        assertTrue(KeyManager.looksEncrypted(bytes))
    }

    @Test
    fun `plain openssh pkcs8 and sec1 keys pass the gate`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val plainOpenSsh = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "plain").toByteArray()
        KeyPolicy.rejectUnsupportedFormat(plainOpenSsh)
        assertFalse(KeyManager.looksEncrypted(plainOpenSsh))
        assertEquals(KeyPolicy.OpenSshV1Header("none", "none"), KeyPolicy.openSshV1Header(String(plainOpenSsh)))

        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pkcs8 = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(ec.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        KeyPolicy.rejectUnsupportedFormat(pkcs8.toByteArray())
        KeyPolicy.rejectUnsupportedFormat(
            "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEI\n-----END EC PRIVATE KEY-----\n".toByteArray(),
        )
    }

    @Test
    fun `non-key bytes are not a policy matter`() {
        KeyPolicy.rejectUnsupportedFormat(ByteArray(0))
        KeyPolicy.rejectUnsupportedFormat("hello world, not a key".toByteArray())
        assertNull(KeyPolicy.openSshV1Header("-----BEGIN OPENSSH PRIVATE KEY-----\nnot-base64!!\n"))
    }
}
