package at.least.conch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class BackupCodecTest {

    private fun samplePayload() = BackupCodec.BackupPayload(
        hosts = listOf(
            HostWire(
                id = "h1",
                alias = "prod",
                hostname = "prod.example.com",
                port = 22,
                username = "alice",
                authType = Host.AUTH_PASSWORD,
            )
        ),
        hostSecrets = mapOf("h1" to "s3cret-password"),
        keys = listOf(
            KeyWire(
                id = "k1",
                name = "my-phone",
                algorithm = "ssh-ed25519",
                createdAt = 0L,
                publicLine = "ssh-ed25519 AAAA... my-phone",
                fingerprint = "SHA256:xxx",
            )
        ),
        keySecrets = mapOf("k1" to "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"),
        snippets = listOf(SnippetWire("s1", "disk", "df -h")),
        knownHosts = "[prod.example.com]:2222 ssh-ed25519 AAAA\n",
    )

    @Test
    fun `encrypt decrypt roundtrip preserves everything`() {
        val blob = BackupCodec.encrypt(samplePayload(), "correct horse".toCharArray())
        val out = BackupCodec.decrypt(blob, "correct horse".toCharArray())

        assertEquals(1, out.hosts.size)
        assertEquals("prod", out.hosts[0].alias)
        assertEquals("s3cret-password", out.hostSecrets["h1"])
        assertEquals(1, out.keys.size)
        assertEquals("ssh-ed25519", out.keys[0].algorithm)
        assertTrue(out.keySecrets["k1"]!!.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertEquals(1, out.snippets.size)
        assertEquals("df -h", out.snippets[0].command)
        assertTrue(out.knownHosts.contains("prod.example.com"))
    }

    @Test
    fun `wrong passphrase throws AEADBadTag`() {
        val blob = BackupCodec.encrypt(samplePayload(), "correct horse".toCharArray())
        try {
            BackupCodec.decrypt(blob, "wrong horse".toCharArray())
            fail("expected AEADBadTagException")
        } catch (e: AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun `ciphertext differs per encryption (random salt+iv)`() {
        val a = BackupCodec.encrypt(samplePayload(), "pass123".toCharArray())
        val b = BackupCodec.encrypt(samplePayload(), "pass123".toCharArray())
        assertNotEquals(a.toList(), b.toList())
        assertEquals(a.size, b.size)
    }

    @Test
    fun `format has magic header`() {
        val blob = BackupCodec.encrypt(samplePayload(), "pass123".toCharArray())
        assertEquals("TILDBAK1", String(blob, 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `garbage input rejected without leaking key derivation`() {
        try {
            BackupCodec.decrypt(ByteArray(50), "x".toCharArray())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `unicode passphrases and secrets survive roundtrip`() {
        val payload = samplePayload().copy(
            hostSecrets = mapOf("h1" to "パスワード🔑"),
        )
        val blob = BackupCodec.encrypt(payload, "パスフレーズ123".toCharArray())
        val out = BackupCodec.decrypt(blob, "パスフレーズ123".toCharArray())
        assertEquals("パスワード🔑", out.hostSecrets["h1"])
    }

    @Test
    fun `header layout is magic salt iv ciphertext tag at pinned offsets`() {
        // magic[8] || salt[16] || iv[12] || ciphertext||tag — the same
        // layout iOS BackupCodecTests testHeaderLayout pins. GCM's doFinal
        // appends the 16-byte tag to the ciphertext, so a blob carrying a
        // 1-byte payload plaintext is exactly 8+16+12+1+16 = 53 bytes.
        val tiny = samplePayload().copy(
            hosts = emptyList(), hostSecrets = emptyMap(), keys = emptyList(),
            keySecrets = emptyMap(), snippets = emptyList(), knownHosts = "",
        )
        val plain = BackupCodec.payloadToJson(tiny).toByteArray(Charsets.UTF_8)
        val blob = BackupCodec.encrypt(tiny, "pw".toCharArray())
        assertEquals(8 + 16 + 12 + plain.size + 16, blob.size)

        // salt and iv live at the documented offsets: two encryptions of
        // the same payload must differ in [8,24) and [24,36) but be
        // byte-equal in the magic segment [0,8).
        val other = BackupCodec.encrypt(tiny, "pw".toCharArray())
        assertEquals(
            String(blob, 0, 8, Charsets.US_ASCII),
            String(other, 0, 8, Charsets.US_ASCII),
        )
        assertNotEquals(blob.copyOfRange(8, 24).toList(), other.copyOfRange(8, 24).toList())
        assertNotEquals(blob.copyOfRange(24, 36).toList(), other.copyOfRange(24, 36).toList())
    }

    @Test
    fun `blob with corrupted magic is rejected as bad magic`() {
        val blob = BackupCodec.encrypt(samplePayload(), "pw".toCharArray())
        blob[0] = 'X'.code.toByte()
        try {
            BackupCodec.decrypt(blob, "pw".toCharArray())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message: ${e.message}", e.message!!.contains("bad magic"))
        }
    }

    @Test
    fun `magic-only blob is rejected as too short`() {
        try {
            BackupCodec.decrypt("TILDBAK1".toByteArray(Charsets.US_ASCII), "pw".toCharArray())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message: ${e.message}", e.message!!.contains("too short"))
        }
    }

    @Test
    fun `payload with unknown version is rejected as unsupported version`() {
        // Build the blob by hand (same suite the codec uses) so the ONLY
        // thing decrypt can reject is version=99 — this also pins the KDF
        // parameters: PBKDF2-HMAC-SHA256, 600k iterations, 256-bit key.
        val tiny = samplePayload().copy(
            hosts = emptyList(), hostSecrets = emptyMap(), keys = emptyList(),
            keySecrets = emptyMap(), snippets = emptyList(), knownHosts = "",
        )
        val json = JSONObject(BackupCodec.payloadToJson(tiny)).put("version", 99)
        val passphrase = "pw".toCharArray()
        val salt = ByteArray(16)
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(salt)
        java.security.SecureRandom().nextBytes(iv)
        val key = javax.crypto.spec.SecretKeySpec(
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(passphrase, salt, 600_000, 256))
                .encoded,
            "AES",
        )
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val blob = "TILDBAK1".toByteArray(Charsets.US_ASCII) + salt + iv + cipher.doFinal(
            json.toString().toByteArray(Charsets.UTF_8)
        )
        try {
            BackupCodec.decrypt(blob, passphrase)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message: ${e.message}", e.message!!.contains("Unsupported backup version"))
        }
    }
}
