package at.least.conch

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, self-contained backup format:
 *
 *   magic "TILDBAK1" || salt[16] || iv[12] || ciphertext(AES-256-GCM over JSON)
 *
 * The payload key is derived from a user passphrase with PBKDF2-HMAC-SHA256
 * (600k iterations), NOT from the Android Keystore — backups must restore on
 * a different device. Pure Kotlin: unit tested without Android.
 */
object BackupCodec {

    private const val MAGIC = "TILDBAK1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_BITS = 256

    @Serializable
    data class BackupPayload(
        val version: Int = 1,
        val hosts: List<HostWire> = emptyList(),
        val hostSecrets: Map<String, String> = emptyMap(),
        val keys: List<KeyWire> = emptyList(),
        val keySecrets: Map<String, String> = emptyMap(),
        val snippets: List<SnippetWire> = emptyList(),
        val knownHosts: String = "",
    )

    // ------------------------------------------------------------- encrypt

    fun encrypt(payload: BackupPayload, passphrase: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val plain = payloadToJson(payload).toByteArray(Charsets.UTF_8)

        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)

        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + ct
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): BackupPayload {
        val magicLen = MAGIC.length
        require(blob.size > magicLen + SALT_LEN + IV_LEN + 16) { "Not a Conch backup (too short)" }
        val magic = String(blob, 0, magicLen, Charsets.US_ASCII)
        require(magic == MAGIC) { "Not a Conch backup (bad magic)" }

        val salt = blob.copyOfRange(magicLen, magicLen + SALT_LEN)
        val iv = blob.copyOfRange(magicLen + SALT_LEN, magicLen + SALT_LEN + IV_LEN)
        val ct = blob.copyOfRange(magicLen + SALT_LEN + IV_LEN, blob.size)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ct) // AEADBadTagException on wrong passphrase
        return payloadFromJson(String(plain, Charsets.UTF_8))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // --------------------------------------------------------------- json

    fun payloadToJson(p: BackupPayload): String = ConchJson.encodeToString(BackupPayload.serializer(), p)

    /**
     * SHA-256 of the plaintext JSON — the "did anything change" signal for
     * scheduled exports. Over the PLAINTEXT, not the ciphertext: encrypt()
     * salts and IVs freshly every call, so ciphertext comparison would see
     * phantom changes on identical data.
     */
    fun fingerprint(p: BackupPayload): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(payloadToJson(p).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun payloadFromJson(json: String): BackupPayload {
        val payload = ConchJson.decodeFromString(BackupPayload.serializer(), json)
        require(payload.version == 1) { "Unsupported backup version" }
        return payload
    }
}
