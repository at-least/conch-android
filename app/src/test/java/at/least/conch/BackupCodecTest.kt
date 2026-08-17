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
            JSONObject()
                .put("id", "h1")
                .put("alias", "prod")
                .put("hostname", "prod.example.com")
                .put("port", 22)
                .put("username", "alice")
                .put("authType", "PASSWORD")
        ),
        hostSecrets = mapOf("h1" to "s3cret-password"),
        keys = listOf(
            JSONObject()
                .put("id", "k1")
                .put("name", "my-phone")
                .put("algorithm", "ssh-ed25519")
                .put("publicLine", "ssh-ed25519 AAAA... my-phone")
                .put("fingerprint", "SHA256:xxx")
        ),
        keySecrets = mapOf("k1" to "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"),
        snippets = listOf(
            JSONObject().put("id", "s1").put("label", "disk").put("command", "df -h")
        ),
        knownHosts = "[prod.example.com]:2222 ssh-ed25519 AAAA\n",
    )

    @Test
    fun `encrypt decrypt roundtrip preserves everything`() {
        val blob = BackupCodec.encrypt(samplePayload(), "correct horse".toCharArray())
        val out = BackupCodec.decrypt(blob, "correct horse".toCharArray())

        assertEquals(1, out.hosts.size)
        assertEquals("prod", out.hosts[0].getString("alias"))
        assertEquals("s3cret-password", out.hostSecrets["h1"])
        assertEquals(1, out.keys.size)
        assertEquals("ssh-ed25519", out.keys[0].getString("algorithm"))
        assertTrue(out.keySecrets["k1"]!!.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertEquals(1, out.snippets.size)
        assertEquals("df -h", out.snippets[0].getString("command"))
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
}
