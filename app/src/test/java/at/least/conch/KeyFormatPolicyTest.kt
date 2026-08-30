package at.least.conch

import at.least.conch.KeyPolicy.KeyForm
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The shared private-key format policy (KeyPolicy, mirrored by iOS
 * PrivateKeyCodec): a refused form throws the exact conversion message
 * from the head of the file alone; supported forms pass the gate with the
 * right passphrase decision. Each user-visible sentence is pinned once,
 * as the literal the gate returns.
 */
class KeyFormatPolicyTest {

    private fun rejected(bytes: ByteArray): String =
        assertThrows(IllegalArgumentException::class.java) { KeyPolicy.rejectUnsupportedFormat(bytes) }.message!!

    /** A minimal openssh-key-v1 blob carrying only magic + cipher + kdf names. */
    private fun opensshV1(cipher: String, kdf: String): ByteArray {
        val bin = Buffer.PlainBuffer()
            .putRawBytes("openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1))
            .putString(cipher)
            .putString(kdf)
            .putRawBytes(ByteArray(16))
            .compactData
        return opensshArmor(bin).toByteArray()
    }

    // ---------------------------------------------------------------- refused

    @Test
    fun `a real chacha20-poly1305 openssh key is refused with the re-encrypt command`() {
        assertEquals(
            "This key is encrypted with chacha20-poly1305@openssh.com; " +
                "re-encrypt it with: ssh-keygen -p -Z aes256-ctr -f <key>",
            rejected(testResource("/encrypted_ed25519_chacha20.key")),
        )
    }

    @Test
    fun `cbc and 3des openssh ciphers are refused, naming the cipher`() {
        for (cipher in listOf("aes256-cbc", "aes128-ctr", "3des-cbc")) {
            assertEquals(
                "This key is encrypted with $cipher; re-encrypt it with: ssh-keygen -p -Z aes256-ctr -f <key>",
                rejected(opensshV1(cipher, "bcrypt")),
            )
        }
    }

    @Test
    fun `encrypted pkcs8 is refused`() {
        assertEquals(
            "Encrypted PKCS#8 keys are not supported; convert with: ssh-keygen -p -f <key>  " +
                "(or openssl pkey -in <key> -out plain.pem)",
            rejected("-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIIB...\n".toByteArray()),
        )
    }

    @Test
    fun `legacy proc-type pem is refused, whatever the key type`() {
        val message = "Legacy encrypted PEM keys are not supported; convert with: ssh-keygen -p -f <key>"
        val sec1 = "-----BEGIN EC PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,00\n"
        assertEquals(message, rejected(sec1.toByteArray()))
        // an encrypted PKCS#1 file is a legacy-PEM problem first (same order as iOS)
        assertEquals(message, rejected("-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n".toByteArray()))
    }

    @Test
    fun `plain pkcs1 rsa passes the gate — the post-parse rsa policy refuses it`() {
        val form = KeyPolicy.rejectUnsupportedFormat("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n".toByteArray())
        assertEquals(KeyForm.Other, form)
        val e = assertThrows(IllegalArgumentException::class.java) { KeyPolicy.requireLoginSupported("ssh-rsa") }
        assertEquals(KeyPolicy.RSA_NOT_FOR_LOGIN, e.message)
    }

    @Test
    fun `putty versions other than 3 are refused, naming the version`() {
        fun putty(v: Int) = "PuTTY-User-Key-File-$v: ssh-ed25519\nEncryption: none\n".toByteArray()
        assertEquals(
            "PuTTY v2 keys are not supported; save it as v3 in puttygen " +
                "(or export as OpenSSH: puttygen -O private-openssh)",
            rejected(putty(2)),
        )
        for (v in listOf(1, 4, 10)) {
            assertEquals("v$v", KeyPolicy.Hint.puttyVersion(v), rejected(putty(v)))
        }
        assertEquals(KeyForm.Putty(3, "none"), KeyPolicy.rejectUnsupportedFormat(putty(3)))
    }

    @Test
    fun `putty v3 with an unknown cipher is refused, naming the cipher`() {
        assertEquals(
            "PuTTY keys encrypted with chacha20 are not supported; re-save it in puttygen (aes256-cbc) " +
                "or export as OpenSSH: puttygen -O private-openssh",
            rejected("PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: chacha20\nComment: x\n".toByteArray()),
        )
    }

    // --------------------------------------------------------------- allowed

    @Test
    fun `bcrypt aes256-ctr openssh key passes the gate and still prompts for its passphrase`() {
        val form = KeyPolicy.rejectUnsupportedFormat(testResource("/encrypted_ed25519_openssh.key"))
        assertEquals(KeyForm.OpenSshV1("aes256-ctr", "bcrypt"), form)
        assertTrue(KeyPolicy.needsPassphrase(form))
    }

    @Test
    fun `a putty v3 file with a cipher other than aes256-cbc is refused, naming the cipher`() {
        val odd = "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes128-cbc\n".toByteArray()
        assertEquals(
            "PuTTY keys encrypted with aes128-cbc are not supported; re-save it in puttygen (aes256-cbc) " +
                "or export as OpenSSH: puttygen -O private-openssh",
            rejected(odd),
        )
    }

    @Test
    fun `encrypted putty v3 prompts for a passphrase, plain does not`() {
        val enc = KeyPolicy.rejectUnsupportedFormat(
            "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: aes256-cbc\nComment: x\n".toByteArray(),
        )
        assertEquals(KeyForm.Putty(3, "aes256-cbc"), enc)
        assertTrue(KeyPolicy.needsPassphrase(enc))
        assertFalse(KeyPolicy.needsPassphrase(KeyForm.Putty(3, "none")))
    }

    @Test
    fun `plain openssh pkcs8 and sec1 keys pass the gate`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val plainOpenSsh = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "plain").toByteArray()
        val form = KeyPolicy.rejectUnsupportedFormat(plainOpenSsh)
        assertEquals(KeyForm.OpenSshV1("none", "none"), form)
        assertFalse(KeyPolicy.needsPassphrase(form))

        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pkcs8 = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(ec.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        assertEquals(KeyForm.Other, KeyPolicy.rejectUnsupportedFormat(pkcs8.toByteArray()))
        assertEquals(
            KeyForm.Other,
            KeyPolicy.rejectUnsupportedFormat(
                "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEI\n-----END EC PRIVATE KEY-----\n".toByteArray(),
            ),
        )
    }

    @Test
    fun `non-key bytes are not a policy matter`() {
        assertEquals(KeyForm.Other, KeyPolicy.rejectUnsupportedFormat(ByteArray(0)))
        assertEquals(KeyForm.Other, KeyPolicy.rejectUnsupportedFormat("hello world, not a key".toByteArray()))
        assertNull(KeyPolicy.openSshV1Header("-----BEGIN OPENSSH PRIVATE KEY-----\nnot-base64!!\n"))
    }
}
