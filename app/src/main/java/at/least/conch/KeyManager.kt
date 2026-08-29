package at.least.conch

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class SshKeyInfo(
    val id: String,
    val name: String,
    val algorithm: String, // e.g. ssh-ed25519, ssh-rsa
    val createdAt: Long,
    val publicLine: String, // authorized_keys line
    val fingerprint: String, // SHA256:xxx
)

/**
 * Wire shape of keys.json. Every field is REQUIRED on decode — an entry
 * missing a field (hand-edited or foreign file) fails the whole list load,
 * degrading to empty, matching the org.json getString behavior this format
 * shipped with.
 */
@Serializable
data class KeyWire(
    val id: String,
    val name: String,
    val algorithm: String,
    /**
     * Epoch milliseconds. Written as an integer; read leniently — iOS
     * builds before the shared-format spec wrote a fractional double
     * (`1.7e12 + 0.123`), and a strict Long decode rejected the whole
     * backup. Truncation is the spec'd reading (docs/backup-format.md).
     */
    @Serializable(with = LenientEpochMillisSerializer::class)
    val createdAt: Long,
    val publicLine: String,
    val fingerprint: String,
) {
    fun toInfo(): SshKeyInfo = SshKeyInfo(id, name, algorithm, createdAt, publicLine, fingerprint)

    companion object {
        fun from(k: SshKeyInfo) = KeyWire(k.id, k.name, k.algorithm, k.createdAt, k.publicLine, k.fingerprint)
    }
}

/**
 * Long that also accepts a JSON double (or numeric string) on input,
 * truncating toward zero; always writes a plain integer.
 */
object LenientEpochMillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EpochMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val text = json.decodeJsonElement().jsonPrimitive.content
        return text.toLongOrNull()
            ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
            ?: throw IllegalArgumentException("createdAt is not a number: $text")
    }
}

/**
 * Policy shared with iOS (2026-08-29): RSA is not supported for login on
 * either platform. Generated keys are Ed25519; ECDSA imports stay
 * supported. RSA keys restored from a backup are kept (the shared format
 * carries them) but never offered for auth.
 */
object KeyPolicy {
    const val RSA_NOT_FOR_LOGIN =
        "RSA keys are not supported for login — generate an Ed25519 key instead (ssh-keygen -t ed25519)"

    fun isLoginSupported(algorithm: String): Boolean = algorithm != "ssh-rsa"

    fun requireLoginSupported(algorithm: String) {
        require(isLoginSupported(algorithm)) { RSA_NOT_FOR_LOGIN }
    }
}

/**
 * The key being imported is passphrase-protected (either no passphrase was
 * supplied, or the supplied one was rejected). UI uses this to prompt and
 * re-prompt instead of dumping the user back to a file picker.
 */
class EncryptedKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Manages SSH keypairs. Private halves are stored as PKCS#8 PEM, encrypted by
 * [SecretsStore] (Android Keystore); metadata lives in files/keys/keys.json.
 */
class KeyManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "keys").apply { mkdirs() }
    private val metaFile: File get() = File(dir, "keys.json")

    /**
     * True when the last [list] found keys.json present but unreadable.
     * Their secrets are still in the Keystore and the file is kept as
     * keys.json.corrupt, so writes that would rebuild the list from the
     * empty result (and orphan every key) are refused instead.
     */
    @Volatile
    var metaUnreadable: Boolean = false
        private set

    fun list(): MutableList<SshKeyInfo> {
        val out = mutableListOf<SshKeyInfo>()
        metaUnreadable = false
        try {
            if (metaFile.exists()) {
                val wires = ConchJson.decodeFromString(ListSerializer(KeyWire.serializer()), metaFile.readText())
                out.addAll(wires.map { it.toInfo() })
            }
        } catch (_: Exception) {
            metaUnreadable = true
            // keep a copy for recovery before the next save overwrites it
            runCatching { metaFile.copyTo(File(metaFile.parentFile, "${metaFile.name}.corrupt"), overwrite = true) }
        }
        return out
    }

    /** Persists the key metadata list (atomic write; restore merges via this too). */
    fun save(keys: List<SshKeyInfo>) {
        val arr = keys.map { KeyWire.from(it) }
        AtomicFile.write(metaFile, ConchJson.encodeToString(ListSerializer(KeyWire.serializer()), arr))
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
     * (OpenSSH new format, PKCS#8/PKCS#5 PEM, PuTTY). Passphrase-encrypted
     * keys are supported: [EncryptedKeyException] is thrown when a
     * passphrase is required or the given one is wrong — supply it via
     * [passphrase] and retry. Keys are stored decrypted (Keystore-encrypted
     * at rest), so the passphrase is only needed at import time.
     */
    fun import(name: String, pemBytes: ByteArray, passphrase: CharArray? = null): SshKeyInfo {
        // parsed in memory: a temp file in cacheDir would put the plaintext
        // key on flash (and delete() does not wipe it)
        val provider = loadProvider(pemBytes, passphrase)
        run {
            val privPkcs8 = provider.private.encoded
                ?: throw IllegalArgumentException("Unsupported private key format")
            val publicKey = provider.public
                ?: throw IllegalArgumentException("Cannot parse public key")
            val type = KeyType.fromKey(publicKey)
            KeyPolicy.requireLoginSupported(type.toString())
            val edSeed: ByteArray? = when (type) {
                KeyType.ED25519 -> Ed25519Codec.seedFromPkcs8(privPkcs8)
                else -> null
            }
            val publicPoint: ByteArray? = when (type) {
                KeyType.ED25519 -> Ed25519Codec.publicFromX509(publicKey.encoded)
                else -> null
            }
            return persist(
                name,
                type.toString(),
                privPkcs8,
                publicPoint,
                ed25519Seed = edSeed,
                fallbackPublicKey = publicKey,
            )
        }
    }

    /**
     * Loads a key file through sshj. Sniffs encryption before a passphrase-
     * less load (so the UI can prompt), and with a passphrase treats any
     * failure as a wrong passphrase (sshj surfaces AEAD/tag errors as
     * assorted IOExceptions and RuntimeExceptions).
     */
    private fun loadProvider(pemBytes: ByteArray, passphrase: CharArray?): KeyProvider {
        if (passphrase == null && looksEncrypted(pemBytes)) {
            throw EncryptedKeyException("This key is passphrase-protected")
        }
        val probe = SSHClient()
        return try {
            // Same format detection as the file overloads (OpenSSH v1,
            // PKCS#8/PKCS#5, PuTTY), fed from memory. ISO-8859-1 maps every
            // byte 1:1 so a stray non-ASCII comment cannot corrupt the blob.
            val provider = probe.loadKeys(
                String(pemBytes, Charsets.ISO_8859_1),
                null,
                passphrase?.let { PasswordUtils.createOneOff(it) },
            )
            // FileKeyProvider parses lazily — force it inside this try so a
            // wrong passphrase throws here, not at first use.
            provider.private
            provider
        } catch (e: Exception) {
            throw if (passphrase != null || mentionsEncryption(e)) {
                EncryptedKeyException(
                    if (passphrase != null) {
                        "Wrong passphrase (or unreadable key): ${e.message}"
                    } else {
                        "This key is passphrase-protected"
                    },
                    e,
                )
            } else {
                e
            }
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
        check(!metaUnreadable) { UNREADABLE_META }
        keys.add(key)
        save(keys)
        return key
    }

    fun delete(id: String) {
        val keys = list()
        check(!metaUnreadable) { UNREADABLE_META }
        keys.removeAll { it.id == id }
        save(keys)
        SecretsStore.delete("key-priv:$id")
    }

    /**
     * The stored private key as PEM (OpenSSH v1 for ed25519, PKCS#8
     * otherwise; `ssh -i` and ssh-keygen accept both). No-lock-in export —
     * parity driver: Termius/JuiceSSH data-lock-in. UNENCRYPTED on disk;
     * UI must warn.
     */
    fun exportPem(id: String): String? = SecretsStore.get("key-priv:$id")

    fun byId(id: String): SshKeyInfo? = list().firstOrNull { it.id == id }

    /**
     * Loads the private key (decrypting at rest) into an sshj KeyProvider.
     *
     * Failure paths are user-actionable by design (ConnectBot #2066 lesson):
     * a missing secret (Keystore reset invalidated the encrypted blobs —
     * decryption failure reads as absent) and unparseable stored material
     * both throw [IllegalStateException] whose message starts with
     * [MISSING_KEY_PREFIX] so describeError surfaces it verbatim and the
     * reconnect loop treats it as terminal — no retry can bring the key
     * material back. Backup restores re-import key material, so a healthy
     * backup on a new device is NOT a missing-secret scenario.
     */
    fun loadKeyProvider(client: SSHClient, id: String): KeyProvider {
        val info = byId(id)
        val keyName = info?.name ?: id.take(8)
        if (info != null && !KeyPolicy.isLoginSupported(info.algorithm)) {
            // a key that only arrived through a backup; terminal like a missing key
            error("$MISSING_KEY_PREFIX '$keyName' is an RSA key — ${KeyPolicy.RSA_NOT_FOR_LOGIN}")
        }
        val pem = SecretsStore.get("key-priv:$id")
            ?: error(
                "$MISSING_KEY_PREFIX '$keyName' has no private key on this device " +
                    "(a Keystore reset invalidates stored key material) — " +
                    "re-import the key, then edit the host to use it",
            )
        // Parsed straight from the decrypted string: sshj's 3-arg loadKeys
        // reads the PEM from memory, so the private key never lands on the
        // filesystem in the clear. (Stored material is always PKCS#8 or
        // OpenSSH v1 — see persist() — both of which sshj detects from
        // content exactly as it would from a file.)
        return try {
            val provider = client.loadKeys(pem, null, null)
            // FileKeyProvider parses lazily — force it now, inside the wrap,
            // so garbage throws the clear message instead of failing at auth
            provider.public
            provider.private
            provider
        } catch (e: Exception) {
            throw IllegalStateException(
                "$MISSING_KEY_PREFIX '$keyName' could not be read (${e.message}) — " +
                    "re-import the key, then edit the host to use it",
                e,
            )
        }
    }

    companion object {
        /**
         * Prefix of the connect-time errors [loadKeyProvider] throws when the
         * key material is missing or unreadable. describeError passes the
         * message through and SshSession.isTerminalFailure matches the prefix
         * to stop the reconnect loop — retries cannot restore key material.
         */
        const val MISSING_KEY_PREFIX = "Stored key unavailable:"

        const val UNREADABLE_META =
            "The key list (keys.json) is unreadable — it was kept as keys.json.corrupt; " +
                "restore or delete it before adding or removing keys"

        /**
         * Cheap sniff for passphrase-protected key material, before handing
         * it to sshj: legacy PEM (`Proc-Type: 4,ENCRYPTED`), PKCS#8 encrypted
         * header, or OpenSSH v1 whose cipher/KDF is not "none" (what
         * `ssh-keygen -N` writes). Headers only — key bodies are never read.
         */
        internal fun looksEncrypted(b: ByteArray): Boolean {
            if (b.size < "-----BEGIN".length) return false
            val head = b.copyOfRange(0, minOf(b.size, 512)).toString(Charsets.ISO_8859_1)
            if (head.contains("ENCRYPTED PRIVATE KEY")) return true
            if (head.contains("Proc-Type: 4,ENCRYPTED")) return true
            if (!head.contains("OPENSSH PRIVATE KEY")) return false
            return opensshV1BodyEncrypted(head)
        }

        /**
         * Decode enough base64 body to parse the openssh-key-v1 header:
         * magic, then len-prefixed ciphername and kdfname. Encrypted iff
         * either is not "none" (what `ssh-keygen -N` writes).
         */
        private fun opensshV1BodyEncrypted(head: String): Boolean {
            val body = head.lineSequence().drop(1).firstOrNull { it.isNotBlank() } ?: return false
            val bin = runCatching { Base64.getMimeDecoder().decode(body.take(400)) }.getOrNull()
                ?: return false
            val magic = "openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1)
            if (bin.size < magic.size) return false
            if (!bin.copyOfRange(0, magic.size).contentEquals(magic)) return false
            var i = magic.size
            val cipher = lengthPrefixedField(bin, i) ?: return false
            i = cipher.second
            val kdf = lengthPrefixedField(bin, i) ?: return false
            return cipher.first != "none" || kdf.first != "none"
        }

        /** Reads a 4-byte length-prefixed string at [i]; null when malformed. */
        private fun lengthPrefixedField(bin: ByteArray, i: Int): Pair<String, Int>? {
            if (i + 4 > bin.size) return null
            var len = 0
            for (k in 0 until 4) len = (len shl 8) or (bin[i + k].toInt() and 0xff)
            if (len < 0 || len > 64 || i + 4 + len > bin.size) return null
            return String(bin, i + 4, len, Charsets.ISO_8859_1) to i + 4 + len
        }

        private fun mentionsEncryption(e: Throwable): Boolean {
            val m = (e.message ?: "").lowercase()
            return "encrypted" in m || "passphrase" in m || "password" in m
        }

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
