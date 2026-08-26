package at.least.conch

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets (host passwords, private keys) with an AES-256-GCM key held
 * in the Android hardware keystore. Ciphertext format per alias:
 * base64(iv[12] || ciphertext+tag).
 */
object SecretsStore {

    private const val MASTER_ALIAS = "conchapp-master"
    private const val PREFS = "conchapp_secrets"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(alias: String, plaintext: String) {
        check(::prefs.isInitialized) { "SecretsStore not initialised" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(alias, encode(iv, ct)).apply()
    }

    @Synchronized
    fun get(alias: String): String? {
        check(::prefs.isInitialized) { "SecretsStore not initialised" }
        val blob = prefs.getString(alias, null) ?: return null
        val (iv, ct) = decode(blob) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when an entry exists regardless of whether it currently decrypts —
     * distinguishes "never stored" from "stored but the keystore is having a
     * bad day" (get() returns null for both, and overwriting the latter
     * destroys the data once the keystore recovers).
     */
    @Synchronized
    fun contains(alias: String): Boolean {
        check(::prefs.isInitialized) { "SecretsStore not initialised" }
        return prefs.contains(alias)
    }

    @Synchronized
    fun delete(alias: String) {
        check(::prefs.isInitialized) { "SecretsStore not initialised" }
        prefs.edit().remove(alias).apply()
    }

    private fun encode(iv: ByteArray, ct: ByteArray): String {
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun decode(s: String): Pair<ByteArray, ByteArray>? {
        val all = android.util.Base64.decode(s, android.util.Base64.NO_WRAP) ?: return null
        if (all.size < 13) return null
        return all.copyOfRange(0, 12) to all.copyOfRange(12, all.size)
    }
}
