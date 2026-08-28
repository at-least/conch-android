package at.least.conch

import android.content.Context
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * Corrupted / edge-case private key material, in the spirit of sshj's
 * CorruptedPublicKeyTest and paramiko's funky-padding keys: everything our
 * Ed25519Codec emits (or a user hands us) must either load correctly or fail
 * cleanly — never hang, never load the wrong key silently.
 */
class CorruptedKeyTest {

    // ---------------------------------------------- OpenSSH v1 blob surgery

    /** Mutable view of the unencrypted openssh-key-v1 private blob. */
    private class OpenSshKeyBlob(val bytes: ByteArray) {
        var pos = 0

        fun u32(): Int =
            ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
                    .also { pos += 4 }

        fun str(): ByteArray {
            val len = u32()
            val b = bytes.copyOfRange(pos, pos + len)
            pos += len
            return b
        }

        val check1Offset: Int
        val check2Offset: Int
        val paddingOffset: Int
        val paddingLength: Int

        init {
            pos += "openssh-key-v1\u0000".toByteArray().size // magic
            str() // ciphername
            str() // kdfname
            str() // kdfoptions
            u32() // nkeys
            str() // pubkey blob
            u32() // priv section outer length
            check1Offset = pos
            u32()
            check2Offset = pos
            u32()
            str() // keytype
            str() // pub
            str() // priv (seed + pub)
            str() // comment
            paddingOffset = pos
            paddingLength = bytes.size - pos
        }
    }

    private fun pemFor(blob: ByteArray): String {
        val b64 = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(blob)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    private fun loadExpectSuccess(pem: String, expectedPublic: ByteArray) {
        val tmp = File.createTempFile("conch-corrupt", ".key")
        try {
            tmp.writeText(pem)
            val provider = SSHClient().loadKeys(tmp.absolutePath)
            val wire = net.schmizz.sshj.common.Buffer.PlainBuffer()
                .apply { putPublicKey(provider.public) }.getCompactData()
            assertTrue("loaded key must match expected public point", wire.contentEquals(expectedPublic))
        } finally {
            tmp.delete()
        }
    }

    private fun loadExpectFailure(pem: String, because: String) {
        val tmp = File.createTempFile("conch-corrupt", ".key")
        try {
            tmp.writeText(pem)
            try {
                val provider = SSHClient().loadKeys(tmp.absolutePath)
                provider.public // force lazy parse
                provider.private
                fail("expected load failure: $because")
            } catch (expected: AssertionError) {
                throw expected
            } catch (_: Exception) {
            }
        } finally {
            tmp.delete()
        }
    }

    private fun wireBlobFor(pub: ByteArray): ByteArray = Ed25519Codec.ed25519SshBlob(pub)

    // ------------------------------------------------------ generated keys

    @Test
    fun `generated key loads and round-trips its public point`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "corrupt")
        loadExpectSuccess(pem, wireBlobFor(pub))
    }

    @Test
    fun `five random keys all load (mini fuzz)`() {
        repeat(5) {
            val (seed, pub) = Ed25519Codec.generateKeyPair()
            loadExpectSuccess(Ed25519Codec.openSshPrivateKeyPem(seed, pub, "fuzz$it"), wireBlobFor(pub))
        }
    }

    @Test
    fun `comment length that needs no padding loads`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        // priv section size = 131 + comment; "12345" makes it 136 = 8*17 → zero pad bytes
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "12345")
        val blob = OpenSshKeyBlob(decodePem(pem))
        assertEquals("fixture must be padding-free", 0, blob.paddingLength)
        loadExpectSuccess(pem, wireBlobFor(pub))
    }

    @Test
    fun `generated padding is spec-conformant sequence`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "x")
        val raw = decodePem(pem)
        val parsed = OpenSshKeyBlob(raw)
        val padBytes = raw.copyOfRange(parsed.paddingOffset, raw.size)
        assertEquals((1..padBytes.size).map { it.toByte() }, padBytes.toList())
        loadExpectSuccess(pem, wireBlobFor(pub))
    }

    private fun decodePem(pem: String): ByteArray {
        val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(b64)
    }

    // ------------------------------------------------------- corrupted keys

    @Test
    fun `mismatched check bytes are rejected`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "chk")
        val raw = decodePem(pem)
        val parsed = OpenSshKeyBlob(raw)
        val mutated = raw.copyOf()
        mutated[parsed.check2Offset] = ((mutated[parsed.check2Offset].toInt() + 1) and 0xFF).toByte()
        loadExpectFailure(pemFor(mutated), "check1 != check2")
    }

    @Test
    fun `corrupted padding is rejected by sshj`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "pad")
        val raw = decodePem(pem)
        val parsed = OpenSshKeyBlob(raw)
        if (parsed.paddingLength > 0) {
            val mutated = raw.copyOf()
            mutated[parsed.paddingOffset] = 0x7F // spec says padding must be 1,2,3,...
            loadExpectFailure(pemFor(mutated), "padding byte 0x7F violates the spec")
        }
    }

    @Test
    fun `flipped seed byte still loads but never matches the real public line`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "flip")
        val raw = decodePem(pem)
        val parsed = OpenSshKeyBlob(raw)
        // seed starts right after keytype string inside the priv section:
        // check1(4) check2(4) keytype(4+11) pub(4+32) → priv string header(4) → seed
        val seedOffset = parsed.check1Offset + 4 + 4 + (4 + 11) + (4 + 32) + 4
        val mutated = raw.copyOf()
        mutated[seedOffset] = (mutated[seedOffset].toInt() xor 0x55).toByte()
        // sshj trusts the embedded public section, so it loads with the SAME
        // public — but such a pair can no longer authenticate (priv != pub)
        loadExpectSuccess(pemFor(mutated), wireBlobFor(pub))
    }

    @Test
    fun `truncated base64 body fails cleanly`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "trunc")
        val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        val truncated = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            b64.dropLast(10) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        loadExpectFailure(truncated, "truncated base64")
    }

    @Test
    fun `garbage content fails cleanly`() {
        loadExpectFailure(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nZ2FyYmFnZQ==\n-----END OPENSSH PRIVATE KEY-----\n",
            "valid base64, garbage content",
        )
    }

    @Test
    fun `empty file fails cleanly`() {
        val tmp = File.createTempFile("conch-empty", ".key")
        try {
            tmp.writeText("")
            try {
                SSHClient().loadKeys(tmp.absolutePath)
                fail("empty key file must not load")
            } catch (_: Exception) {
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `wrong magic fails cleanly`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "magic")
        val raw = decodePem(pem)
        val mutated = raw.copyOf()
        mutated[13] = 'X'.code.toByte() // break "openssh-key-v1\0"
        loadExpectFailure(pemFor(mutated), "bad magic")
    }

    // ----------------------------------- stored-key loss at connect (plan 1.4)

    /**
     * KeyManager over a temp filesDir, optionally seeded with keys.json.
     * [cacheDir] defaults to that same dir; pass an unusable one to prove a
     * path does not need scratch space.
     */
    private fun keyManager(keysJson: String? = null, cacheDir: File? = null): Pair<KeyManager, File> {
        val dir = Files.createTempDirectory("conch-keys").toFile()
        dir.deleteOnExit()
        val context = io.mockk.mockk<Context>()
        io.mockk.every { context.filesDir } returns dir
        io.mockk.every { context.cacheDir } returns (cacheDir ?: dir)
        keysJson?.let {
            val metaDir = File(dir, "keys").apply { mkdirs() }
            File(metaDir, "keys.json").writeText(it)
        }
        return KeyManager(context) to dir
    }

    /** keys.json as written after importing "prod-ed25519". */
    private val storedKeyJson = """
        [{"id":"11111111-2222-3333-4444-555555555555","name":"prod-ed25519",
          "algorithm":"ssh-ed25519","createdAt":1750000000000,
          "publicLine":"ssh-ed25519 AAAA prod-ed25519","fingerprint":"SHA256:x"}]
    """.trimIndent()

    @Test
    fun `an unreadable keys_json refuses writes that would orphan every stored key`() {
        val (km, dir) = keyManager("{ not json ]")
        assertTrue(km.list().isEmpty())
        assertTrue(km.metaUnreadable)
        assertTrue(File(dir, "keys/keys.json.corrupt").exists())
        io.mockk.mockkObject(SecretsStore) {
            io.mockk.every { SecretsStore.put(any(), any()) } returns Unit
            io.mockk.every { SecretsStore.delete(any()) } returns Unit
            try {
                km.generate("new-key")
                fail("generate must not rewrite an unreadable key list")
            } catch (e: IllegalStateException) {
                assertEquals(KeyManager.UNREADABLE_META, e.message)
            }
            try {
                km.delete("whatever")
                fail("delete must not rewrite an unreadable key list")
            } catch (e: IllegalStateException) {
                assertEquals(KeyManager.UNREADABLE_META, e.message)
            }
        }
        assertEquals("the corrupt file is left for recovery", "{ not json ]", File(dir, "keys/keys.json").readText())
    }

    @Test
    fun `missing keystore secret fails with actionable message and stops reconnect`() {
        // the Keystore-reset scenario: keys.json still lists the key, but
        // the encrypted blob no longer decrypts — get() reads as absent
        val (km, _) = keyManager(storedKeyJson)
        io.mockk.mockkObject(SecretsStore) {
            io.mockk.every { SecretsStore.get(any()) } returns null
            try {
                km.loadKeyProvider(SSHClient(), "11111111-2222-3333-4444-555555555555")
                fail("missing secret must throw")
            } catch (e: IllegalStateException) {
                val msg = SshConnectionFactory.describeError(e)
                assertTrue(msg.startsWith(KeyManager.MISSING_KEY_PREFIX))
                assertTrue("key name must appear: $msg", msg.contains("prod-ed25519"))
                assertTrue("must say what to do: $msg", msg.contains("re-import"))
                assertTrue(
                    "must be terminal — no retry can restore key material",
                    SshSession.isTerminalFailure(msg),
                )
            }
        }
    }

    @Test
    fun `unreadable stored pem fails with actionable message`() {
        val (km, _) = keyManager(storedKeyJson)
        io.mockk.mockkObject(SecretsStore) {
            io.mockk.every { SecretsStore.get(any()) } returns "definitely not a pem"
            try {
                km.loadKeyProvider(SSHClient(), "11111111-2222-3333-4444-555555555555")
                fail("garbage material must throw")
            } catch (e: IllegalStateException) {
                val msg = SshConnectionFactory.describeError(e)
                assertTrue(msg.startsWith(KeyManager.MISSING_KEY_PREFIX))
                assertTrue("key name must appear: $msg", msg.contains("prod-ed25519"))
                assertTrue(SshSession.isTerminalFailure(msg))
            }
        }
    }

    /**
     * The decrypted private key must never be written to the filesystem.
     * Everything else in the app keeps key material Keystore-encrypted at
     * rest; a connect path that spills a plaintext PEM into the app cache —
     * once per connect, and left behind entirely if the process is killed
     * before the cleanup — would quietly undo that. sshj parses the PEM from
     * memory instead, and this pins it.
     */
    @Test
    fun `loading a stored key writes no plaintext key material to disk`() {
        // An UNUSABLE cache dir (a regular file, so createTempFile there
        // cannot succeed): the load must not want scratch space at all. This
        // is the teeth of the test — a temp-file implementation deletes its
        // spill on the way out, so counting leftover files proves nothing.
        val noCache = Files.createTempFile("conch-not-a-dir", null).toFile()
            .apply { deleteOnExit() }
        val (km, dir) = keyManager(storedKeyJson, cacheDir = noCache)

        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "prod-ed25519")

        io.mockk.mockkObject(SecretsStore) {
            io.mockk.every { SecretsStore.get(any()) } returns pem
            val provider = km.loadKeyProvider(SSHClient(), "11111111-2222-3333-4444-555555555555")
            assertTrue(
                "the loaded key must be the stored one",
                net.schmizz.sshj.common.Buffer.PlainBuffer()
                    .apply { putPublicKey(provider.public) }.getCompactData()
                    .contentEquals(wireBlobFor(pub)),
            )
        }

        // the cache dir is covered by being unusable; this catches a spill
        // into filesDir instead
        assertTrue(
            "no file under filesDir may hold the private key in the clear",
            dir.walkTopDown().filter { it.isFile }.none { it.readText().contains("PRIVATE KEY") },
        )
    }

    @Test
    fun `key gone from metadata too still identifies which key failed`() {
        val (km, _) = keyManager() // no keys.json at all
        io.mockk.mockkObject(SecretsStore) {
            io.mockk.every { SecretsStore.get(any()) } returns null
            try {
                km.loadKeyProvider(SSHClient(), "9999888877776666")
                fail("missing secret must throw")
            } catch (e: IllegalStateException) {
                val msg = SshConnectionFactory.describeError(e)
                assertTrue("id prefix must appear: $msg", msg.contains("99998888"))
                assertTrue(SshSession.isTerminalFailure(msg))
            }
        }
    }
}
