package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException

/** CONCHBAK container pins (docs/backup-format.md §1); iOS BackupCodecTests mirrors these. */
class BackupCodecTest {

    private fun samplePayload() = BackupPayload(
        exportedAt = "2026-08-29T05:30:00Z",
        origin = BackupOrigin("android", "test"),
        hosts = listOf(
            BackupHost(
                id = "h1",
                name = "prod",
                hostname = "prod.example.com",
                username = "alice",
                auth = BackupAuth(BackupAuth.METHOD_PASSWORD, password = "s3cret-password"),
            ),
        ),
        keys = listOf(
            BackupKey(
                id = "k1",
                name = "my-phone",
                algorithm = "ssh-ed25519",
                createdAt = "2025-01-01T00:00:00Z",
                publicKey = "ssh-ed25519 AAAA... my-phone",
                fingerprint = "SHA256:xxx",
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n",
            ),
        ),
        snippets = listOf(BackupSnippet("s1", "disk", "df -h")),
        knownHosts = listOf(BackupKnownHost("prod.example.com", 2222, "ssh-ed25519", "AAAA")),
    )

    private fun empty() = BackupPayload()

    @Test
    fun `encrypt decrypt roundtrip preserves everything`() {
        val blob = BackupCodec.encrypt(samplePayload(), "correct horse".toCharArray())
        val out = BackupCodec.decrypt(blob, "correct horse".toCharArray())
        assertEquals(samplePayload(), out)
    }

    @Test
    fun `wrong passphrase throws AEADBadTag`() {
        val blob = BackupCodec.encrypt(samplePayload(), "correct horse".toCharArray())
        try {
            BackupCodec.decrypt(blob, "wrong horse".toCharArray())
            fail("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
        }
    }

    @Test
    fun `ciphertext differs per encryption (random salt+nonce)`() {
        val a = BackupCodec.encrypt(samplePayload(), "pass123".toCharArray())
        val b = BackupCodec.encrypt(samplePayload(), "pass123".toCharArray())
        assertNotEquals(a.toList(), b.toList())
        assertEquals(a.size, b.size)
    }

    @Test
    fun `header layout is pinned at documented offsets`() {
        // "CONCHBAK" | version u16=1 | kdf u8=1 | cipher u8=1 | iterations u32 |
        // salt[16] | nonce[12] | ciphertext || tag[16]
        val plain = BackupCodec.payloadToJson(empty()).toByteArray(Charsets.UTF_8)
        val blob = BackupCodec.encrypt(empty(), "pw".toCharArray())
        assertEquals(44 + plain.size + 16, blob.size)
        assertEquals("CONCHBAK", String(blob, 0, 8, Charsets.US_ASCII))
        val buf = ByteBuffer.wrap(blob, 8, 8)
        assertEquals(1, buf.short.toInt())
        assertEquals(1, buf.get().toInt())
        assertEquals(1, buf.get().toInt())
        assertEquals(600_000, buf.int)

        val other = BackupCodec.encrypt(empty(), "pw".toCharArray())
        assertEquals(blob.copyOfRange(0, 16).toList(), other.copyOfRange(0, 16).toList())
        assertNotEquals(blob.copyOfRange(16, 32).toList(), other.copyOfRange(16, 32).toList())
        assertNotEquals(blob.copyOfRange(32, 44).toList(), other.copyOfRange(32, 44).toList())
    }

    @Test
    fun `garbage input rejected before key derivation`() {
        try {
            BackupCodec.decrypt(ByteArray(50), "x".toCharArray())
            fail("expected FormatException")
        } catch (_: BackupCodec.FormatException) {
        }
    }

    @Test
    fun `unicode passphrases and secrets survive roundtrip`() {
        val payload = samplePayload().copy(
            hosts = listOf(BackupHost(id = "h1", auth = BackupAuth(password = "パスワード🔑"))),
        )
        val blob = BackupCodec.encrypt(payload, "パスフレーズ123".toCharArray())
        val out = BackupCodec.decrypt(blob, "パスフレーズ123".toCharArray())
        assertEquals("パスワード🔑", out.hosts[0].auth.password)
    }

    @Test
    fun `corrupted magic is rejected as bad magic`() {
        val blob = BackupCodec.encrypt(samplePayload(), "pw".toCharArray())
        blob[0] = 'X'.code.toByte()
        assertRejected(blob, "bad magic")
    }

    @Test
    fun `magic-only blob is rejected as too short`() {
        assertRejected("CONCHBAK".toByteArray(Charsets.US_ASCII), "too short")
    }

    @Test
    fun `unknown format version is rejected before key derivation`() {
        val blob = BackupCodec.encrypt(samplePayload(), "pw".toCharArray())
        blob[9] = 2 // version u16 low byte
        assertRejected(blob, "Unsupported backup version")
    }

    @Test
    fun `unknown kdf or cipher id is rejected`() {
        val blob = BackupCodec.encrypt(samplePayload(), "pw".toCharArray())
        blob[10] = 7
        assertRejected(blob, "Unsupported backup parameters")
        blob[10] = 1
        blob[11] = 7
        assertRejected(blob, "Unsupported backup parameters")
    }

    @Test
    fun `iteration count is authenticated — tampering fails the tag`() {
        // The header is GCM associated data: lowering the iteration count
        // (to make brute force cheaper) must not yield a decryptable file.
        val blob = BackupCodec.encrypt(samplePayload(), "pw".toCharArray())
        ByteBuffer.wrap(blob, 12, 4).putInt(1000)
        try {
            BackupCodec.decrypt(blob, "pw".toCharArray())
            fail("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
        }
    }

    @Test
    fun `iteration count is read from the header`() {
        // A writer with a different (valid) count still decrypts — the
        // parameter lives in the file, not in the reader.
        val blob = BackupCodec.encrypt(samplePayload(), "pw".toCharArray(), iterations = 1234)
        assertEquals(1234, ByteBuffer.wrap(blob, 12, 4).int)
        assertEquals(samplePayload(), BackupCodec.decrypt(blob, "pw".toCharArray()))
    }

    @Test
    fun `payload json is canonical — sorted keys no whitespace raw unicode`() {
        val json = BackupCodec.payloadToJson(samplePayload().copy(hosts = listOf(BackupHost(id = "h", name = "生產/機"))))
        assertTrue(json, json.startsWith("{\"exportedAt\":"))
        assertTrue(json, json.contains("\"hosts\":[{\"auth\":{\"method\":\"password\"},\"exposeFiles\":false"))
        assertTrue(json, json.contains("\"name\":\"生產/機\""))
        assertTrue(json, !json.contains(": ") && !json.contains(", ") && !json.contains("\\u") && !json.contains("\\/"))
        // optionals are absent, never null
        assertTrue(json, !json.contains("null"))
    }

    @Test
    fun `fingerprint ignores exportedAt and tracks data`() {
        val a = samplePayload()
        val later = a.copy(exportedAt = "2030-01-01T00:00:00Z")
        val edited = a.copy(snippets = emptyList())
        assertEquals(BackupCodec.fingerprint(a), BackupCodec.fingerprint(later))
        assertNotEquals(BackupCodec.fingerprint(a), BackupCodec.fingerprint(edited))
    }

    private fun assertRejected(blob: ByteArray, message: String) {
        try {
            BackupCodec.decrypt(blob, "pw".toCharArray())
            fail("expected FormatException")
        } catch (e: BackupCodec.FormatException) {
            assertTrue("message: ${e.message}", e.message!!.contains(message))
        }
    }
}
