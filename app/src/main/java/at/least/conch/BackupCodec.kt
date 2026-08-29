package at.least.conch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CONCHBAK container (docs/backup-format.md §1), byte-compatible with iOS:
 *
 *   "CONCHBAK" | version u16 | kdf u8 | cipher u8 | iterations u32 |
 *   salt[16] | nonce[12] | AES-256-GCM(canonical JSON), header as AAD
 *
 * The key comes from the passphrase via PBKDF2-HMAC-SHA256, never from the
 * Android Keystore — backups must restore on a different device. Pure
 * Kotlin: unit tested without Android.
 */
object BackupCodec {

    const val MAGIC = "CONCHBAK"
    const val FORMAT_VERSION = 1
    const val KDF_PBKDF2_HMAC_SHA256 = 1
    const val CIPHER_AES_256_GCM = 1
    const val ITERATIONS = 600_000
    const val MAX_ITERATIONS = 10_000_000
    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val HEADER_LEN = 8 + 2 + 1 + 1 + 4 + SALT_LEN + NONCE_LEN // 44
    private const val KEY_BITS = 256

    /** Payload JSON contract: unknown keys skipped, absent ≡ default, optionals omitted (never `null`). */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    class FormatException(message: String) : IllegalArgumentException(message)

    // ------------------------------------------------------------- encrypt

    fun encrypt(
        payload: BackupPayload,
        passphrase: CharArray,
        random: SecureRandom = SecureRandom(),
        iterations: Int = ITERATIONS,
    ): ByteArray {
        val plain = payloadToJson(payload).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val header = header(iterations, salt, nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_LEN * 8, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): BackupPayload {
        val iterations = checkHeader(blob)
        val header = blob.copyOfRange(0, HEADER_LEN)
        val salt = blob.copyOfRange(16, 16 + SALT_LEN)
        val nonce = blob.copyOfRange(32, 32 + NONCE_LEN)
        val ct = blob.copyOfRange(HEADER_LEN, blob.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_LEN * 8, nonce))
        cipher.updateAAD(header)
        val plain = cipher.doFinal(ct) // AEADBadTagException: wrong passphrase or tampered
        return payloadFromJson(String(plain, Charsets.UTF_8))
    }

    /**
     * Rejects, in spec order and before any key derivation: too short, bad
     * magic, unknown version, unknown KDF/cipher or an iteration count out
     * of range. Returns the iteration count to derive with.
     */
    private fun checkHeader(blob: ByteArray): Int {
        val problem = when {
            blob.size <= HEADER_LEN + TAG_LEN -> "Not a Conch backup (too short)"
            String(blob, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC -> "Not a Conch backup (bad magic)"
            else -> null
        }
        if (problem != null) throw FormatException(problem)
        val buf = ByteBuffer.wrap(blob, MAGIC.length, HEADER_LEN - MAGIC.length)
        val version = buf.short.toInt() and 0xFFFF
        val kdf = buf.get().toInt() and 0xFF
        val cipherId = buf.get().toInt() and 0xFF
        val iterations = buf.int
        val unsupported = when {
            version != FORMAT_VERSION -> "Unsupported backup version $version"
            kdf != KDF_PBKDF2_HMAC_SHA256 || cipherId != CIPHER_AES_256_GCM -> "Unsupported backup parameters"
            iterations !in 1..MAX_ITERATIONS -> "Unsupported backup parameters"
            else -> null
        }
        if (unsupported != null) throw FormatException(unsupported)
        return iterations
    }

    private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC.toByteArray(Charsets.US_ASCII))
            .putShort(FORMAT_VERSION.toShort())
            .put(KDF_PBKDF2_HMAC_SHA256.toByte())
            .put(CIPHER_AES_256_GCM.toByte())
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // --------------------------------------------------------------- json

    /** Canonical JSON (§2): sorted keys, no whitespace, raw non-ASCII. */
    fun payloadToJson(p: BackupPayload): String {
        val tree = json.encodeToJsonElement(BackupPayload.serializer(), p)
        return json.encodeToString(JsonElement.serializer(), canonical(tree))
    }

    fun payloadFromJson(text: String): BackupPayload =
        json.decodeFromString(BackupPayload.serializer(), text)

    /**
     * SHA-256 of the canonical plaintext with `exportedAt` removed — the
     * "did anything change" signal for scheduled exports. Over the
     * PLAINTEXT because salt and nonce are fresh every encrypt.
     */
    fun fingerprint(p: BackupPayload): String {
        val stable = p.copy(exportedAt = BackupTime.EPOCH)
        return MessageDigest.getInstance("SHA-256")
            .digest(payloadToJson(stable).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonical(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(e.map(::canonical))
        else -> e
    }
}
