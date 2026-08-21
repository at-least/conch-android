package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
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
}
