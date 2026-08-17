package at.least.conch

import android.content.Context
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

enum class KnownStatus { KNOWN, MISMATCH, UNKNOWN }

/**
 * OpenSSH-format known_hosts store with TOFU (trust-on-first-use) semantics.
 * Pure entry encoding/decoding is kept testable without Android.
 */
class KnownHostsStore(context: Context) {

    private val file: File = File(context.filesDir, "known_hosts")

    fun status(hostname: String, port: Int, key: PublicKey): KnownStatus {
        val blob = blobOf(key)
        var mismatch = false
        for (line in readLines()) {
            val entry = parseEntry(line) ?: continue
            if (entry.host != hostField(hostname, port)) continue
            if (entry.blob.contentEquals(blob)) return KnownStatus.KNOWN
            mismatch = true
        }
        return if (mismatch) KnownStatus.MISMATCH else KnownStatus.UNKNOWN
    }

    fun add(hostname: String, port: Int, key: PublicKey) {
        val line = entryFor(hostname, port, key)
        val existing = readLines().toMutableSet()
        if (line !in existing) {
            existing.add(line)
            file.writeText(existing.joinToString("\n", postfix = "\n"))
        }
    }

    fun algorithmsFor(hostname: String, port: Int): List<String> {
        val host = hostField(hostname, port)
        return readLines().mapNotNull { parseEntry(it) }
            .filter { it.host == host }
            .map { it.algorithm }
            .distinct()
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        fun hostField(hostname: String, port: Int): String =
            if (port == 22) hostname else "[$hostname]:$port"

        fun blobOf(key: PublicKey): ByteArray =
            Buffer.PlainBuffer().apply { putPublicKey(key) }.getCompactData()

        fun fingerprintOf(key: PublicKey): String =
            KeyManager.fingerprintOfBlob(blobOf(key))

        fun typeOf(key: PublicKey): String = KeyType.fromKey(key).toString()

        /** known_hosts line: host algorithm base64(blob). */
        fun entryFor(hostname: String, port: Int, key: PublicKey): String {
            val type = KeyType.fromKey(key).toString()
            val b64 = Base64.getEncoder().encodeToString(blobOf(key))
            return "${hostField(hostname, port)} $type $b64"
        }

        data class Entry(val host: String, val algorithm: String, val blob: ByteArray)

        fun parseEntry(line: String): Entry? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return null
            val blob = try {
                Base64.getDecoder().decode(parts[2])
            } catch (_: Exception) {
                return null
            }
            return Entry(parts[0], parts[1], blob)
        }
    }
}

/**
 * Host key verifier implementing TOFU. When a UI [prompt] is supplied it can
 * ask the user to trust unknown (or warn about changed) keys; otherwise new and
 * changed keys are rejected outright (background sessions must be pre-trusted).
 */
class TofuHostKeyVerifier(
    private val store: KnownHostsStore,
    private val prompt: KeyPrompt? = null,
    private val mainHandler: android.os.Handler? = null,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        when (store.status(hostname, port, key)) {
            KnownStatus.KNOWN -> return true
            KnownStatus.MISMATCH -> {
                val p = prompt ?: return false
                val accepted = ask(hostname, port, key, isChange = true, p)
                if (accepted) {
                    // record the new key so the user is not prompted again;
                    // the superseded entry stays as an accepted variant
                    store.add(hostname, port, key)
                }
                return accepted
            }
            KnownStatus.UNKNOWN -> {
                val p = prompt ?: return false
                val accepted = ask(hostname, port, key, isChange = false, p)
                if (accepted) store.add(hostname, port, key)
                return accepted
            }
        }
    }

    private fun ask(
        hostname: String,
        port: Int,
        key: PublicKey,
        isChange: Boolean,
        p: KeyPrompt,
    ): Boolean {
        val future = CompletableFuture<Boolean>()
        val runnable: () -> Unit = {
            try {
                p(
                    KeyPromptRequest(
                        keyType = KnownHostsStore.typeOf(key),
                        fingerprint = KnownHostsStore.fingerprintOf(key),
                        endpoint = "$hostname:$port",
                        isChange = isChange,
                    )
                ) { accepted -> future.complete(accepted) }
            } catch (_: Exception) {
                future.complete(false)
            }
        }
        mainHandler?.post(runnable) ?: runnable()
        return try {
            future.get(60, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        store.algorithmsFor(hostname, port)
}

data class KeyPromptRequest(
    val keyType: String,
    val fingerprint: String,
    val endpoint: String,
    val isChange: Boolean,
)

typealias KeyPrompt = (KeyPromptRequest, (Boolean) -> Unit) -> Unit
