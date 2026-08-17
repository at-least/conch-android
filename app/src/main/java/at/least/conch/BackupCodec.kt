package at.least.conch

import org.json.JSONArray
import org.json.JSONObject
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

    data class BackupPayload(
        val hosts: List<JSONObject>,               // host entries, no secrets inside
        val hostSecrets: Map<String, String>,      // host id -> plaintext password
        val keys: List<JSONObject>,                // SshKeyInfo entries
        val keySecrets: Map<String, String>,       // key id -> PEM private key
        val snippets: List<JSONObject>,
        val knownHosts: String,
    )

    // ------------------------------------------------------------- encrypt

    fun encrypt(payload: BackupPayload, passphrase: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val plain = payloadToJson(payload).toString().toByteArray(Charsets.UTF_8)

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
        val plain = cipher.doFinal(ct)   // AEADBadTagException on wrong passphrase
        return payloadFromJson(JSONObject(String(plain, Charsets.UTF_8)))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    // --------------------------------------------------------------- json

    fun payloadToJson(p: BackupPayload): JSONObject = JSONObject()
        .put("version", 1)
        .put("hosts", JSONArray(p.hosts))
        .put(
            "hostSecrets",
            JSONObject(p.hostSecrets)
        )
        .put("keys", JSONArray(p.keys))
        .put("keySecrets", JSONObject(p.keySecrets))
        .put("snippets", JSONArray(p.snippets))
        .put("knownHosts", p.knownHosts)

    private fun payloadFromJson(o: JSONObject): BackupPayload {
        require(o.optInt("version", 0) == 1) { "Unsupported backup version" }
        return BackupPayload(
            hosts = o.optJSONArray("hosts")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList(),
            hostSecrets = jsonObjectToStringMap(o.optJSONObject("hostSecrets")),
            keys = o.optJSONArray("keys")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList(),
            keySecrets = jsonObjectToStringMap(o.optJSONObject("keySecrets")),
            snippets = o.optJSONArray("snippets")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList(),
            knownHosts = o.optString("knownHosts", ""),
        )
    }

    private fun jsonObjectToStringMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }
}
