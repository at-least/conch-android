package at.least.conch

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class SshKeyInfo(
    val id: String,
    val name: String,
    val algorithm: String,     // e.g. ssh-ed25519, ssh-rsa
    val createdAt: Long,
    val publicLine: String,    // authorized_keys line
    val fingerprint: String,   // SHA256:xxx
)

/**
 * Manages SSH keypairs. Private halves are stored as PKCS#8 PEM, encrypted by
 * [SecretsStore] (Android Keystore); metadata lives in files/keys/keys.json.
 */
class KeyManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "keys").apply { mkdirs() }
    private val metaFile: File get() = File(dir, "keys.json")

    fun list(): MutableList<SshKeyInfo> {
        val out = mutableListOf<SshKeyInfo>()
        try {
            if (metaFile.exists()) {
                val arr = JSONArray(metaFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        SshKeyInfo(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            algorithm = o.getString("algorithm"),
                            createdAt = o.getLong("createdAt"),
                            publicLine = o.getString("publicLine"),
                            fingerprint = o.getString("fingerprint"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(keys: List<SshKeyInfo>) {
        val arr = JSONArray()
        for (k in keys) {
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("name", k.name)
                    .put("algorithm", k.algorithm)
                    .put("createdAt", k.createdAt)
                    .put("publicLine", k.publicLine)
                    .put("fingerprint", k.fingerprint)
            )
        }
        metaFile.writeText(arr.toString())
    }

    /** Generates a new Ed25519 keypair and stores it encrypted. */
    fun generate(name: String): SshKeyInfo {
        val (seed, publicPoint) = Ed25519Codec.generateKeyPair()
        val pkcs8 = Ed25519Codec.pkcs8FromSeed(seed)
        return persist(
            name = name,
            algorithm = "ssh-ed25519",
            pkcs8 = pkcs8,
            publicPoint = publicPoint,
            ed25519Seed = seed,
        )
    }

    /**
     * Imports an existing private key of any format sshj understands
     * (OpenSSH new format, PKCS#8/PKCS#5 PEM, PuTTY). Encrypted (passphrase)
     * keys are not supported — decrypt them before importing.
     */
    fun import(name: String, pemBytes: ByteArray): SshKeyInfo {
        val tmp = File.createTempFile("import", ".key", context.cacheDir)
        try {
            tmp.writeBytes(pemBytes)
            val probe = SSHClient()
            val provider = probe.loadKeys(tmp.absolutePath)
            val privPkcs8 = provider.private.encoded
                ?: throw IllegalArgumentException("Unsupported private key format")
            val publicKey = provider.public
                ?: throw IllegalArgumentException("Cannot parse public key")
            val type = KeyType.fromKey(publicKey)
            val edSeed: ByteArray? = when (type) {
                KeyType.ED25519 -> Ed25519Codec.seedFromPkcs8(privPkcs8)
                else -> null
            }
            val publicPoint: ByteArray? = when (type) {
                KeyType.ED25519 -> Ed25519Codec.publicFromX509(publicKey.encoded)
                else -> null
            }
            return persist(
                name, type.toString(), privPkcs8, publicPoint,
                ed25519Seed = edSeed, fallbackPublicKey = publicKey,
            )
        } finally {
            tmp.delete()
        }
    }

    private fun persist(
        name: String,
        algorithm: String,
        pkcs8: ByteArray,
        publicPoint: ByteArray?,
        ed25519Seed: ByteArray? = null,
        fallbackPublicKey: java.security.PublicKey? = null,
    ): SshKeyInfo {
        val id = UUID.randomUUID().toString()
        // ed25519 must be stored in OpenSSH v1 format — sshj's PKCS#8 reader
        // cannot convert Ed25519 key material.
        val pem = if (publicPoint != null && ed25519Seed != null && algorithm == "ssh-ed25519") {
            Ed25519Codec.openSshPrivateKeyPem(ed25519Seed, publicPoint, name)
        } else {
            "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8) +
                "\n-----END PRIVATE KEY-----\n"
        }
        SecretsStore.put("key-priv:$id", pem)

        val publicLine: String
        if (publicPoint != null && algorithm == "ssh-ed25519") {
            publicLine = publicLineFor("ssh-ed25519", publicPoint, name)
        } else {
            val pk = fallbackPublicKey ?: throw IllegalArgumentException("Cannot obtain public key")
            publicLine = publicLineFor(pk, name)
        }
        val fingerprint = fingerprintOf(publicLine)

        val key = SshKeyInfo(
            id = id,
            name = name,
            algorithm = algorithm,
            createdAt = System.currentTimeMillis(),
            publicLine = publicLine,
            fingerprint = fingerprint,
        )
        val keys = list()
        keys.add(key)
        save(keys)
        return key
    }

    fun delete(id: String) {
        val keys = list()
        keys.removeAll { it.id == id }
        save(keys)
        SecretsStore.delete("key-priv:$id")
    }

    fun byId(id: String): SshKeyInfo? = list().firstOrNull { it.id == id }

    /** Loads the private key (decrypting at rest) into an sshj KeyProvider. */
    fun loadKeyProvider(client: SSHClient, id: String): KeyProvider {
        val pem = SecretsStore.get("key-priv:$id")
            ?: throw IllegalStateException("Key data not found")
        val tmp = File.createTempFile("conch", ".key", context.cacheDir)
        try {
            tmp.writeText(pem)
            val provider = client.loadKeys(tmp.absolutePath)
            // FileKeyProvider parses lazily — force it now while the file exists
            provider.public
            provider.private
            return provider
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** ssh wire blob = len-prefixed algorithm name + len-prefixed key data. */
        fun sshBlob(algorithm: String, keyData: ByteArray): ByteArray {
            val buf = Buffer.PlainBuffer()
            buf.putString(algorithm)
            buf.putString(keyData)
            return buf.getCompactData()
        }

        fun publicLineFor(algorithm: String, keyData: ByteArray, comment: String): String =
            "$algorithm ${Base64.getEncoder().encodeToString(sshBlob(algorithm, keyData))} $comment"

        fun publicLineFor(publicKey: java.security.PublicKey, comment: String): String {
            val buf = Buffer.PlainBuffer()
            buf.putPublicKey(publicKey)
            val type = KeyType.fromKey(publicKey).toString()
            return "$type ${Base64.getEncoder().encodeToString(buf.getCompactData())} $comment"
        }

        /** SHA256 fingerprint of the ssh wire blob embedded in an authorized_keys line. */
        fun fingerprintOf(publicLine: String): String {
            val parts = publicLine.trim().split(" ")
            val blob = Base64.getDecoder().decode(parts.getOrElse(1) { "" })
            return fingerprintOfBlob(blob)
        }

        fun fingerprintOfBlob(blob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
