package at.least.conch

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
 * Takes the app's files dir (not a Context) so entry encoding/decoding and
 * the full TOFU flow are testable on the JVM.
 */
class KnownHostsStore(filesDir: File) {

    val file: File = File(filesDir, "known_hosts")

    fun status(hostname: String, port: Int, key: PublicKey): KnownStatus {
        val blob = blobOf(key)
        val entries = readLines().mapNotNull(::parseEntry).filter { matchesHost(it.host, hostname, port) }
        return when {
            entries.any { it.blob.contentEquals(blob) } -> KnownStatus.KNOWN
            entries.isNotEmpty() -> KnownStatus.MISMATCH
            else -> KnownStatus.UNKNOWN
        }
    }

    fun add(hostname: String, port: Int, key: PublicKey) {
        // sshj handshakes run on their own threads; two simultaneous TOFU
        // accepts must not lose each other's read-modify-write
        synchronized(this) {
            val line = entryFor(hostname, port, key)
            val existing = readLines().toMutableSet()
            if (line !in existing) {
                existing.add(line)
                AtomicFile.write(file, existing.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun algorithmsFor(hostname: String, port: Int): List<String> {
        return readLines().mapNotNull { parseEntry(it) }
            .filter { matchesHost(it.host, hostname, port) }
            .map { it.algorithm }
            .distinct()
    }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        /** OpenSSH format: `host`, `[host]:port` for non-22; IPv6 always bracketed. */
        fun hostField(hostname: String, port: Int): String {
            val bare = hostname.removePrefix("[").removeSuffix("]")
            return when {
                port == 22 && !bare.contains(":") -> bare
                port == 22 -> "[$bare]"
                else -> "[$bare]:$port"
            }
        }

        /**
         * Entry matches when it equals the current canonical field, or when it
         * was written by an older app version that stored IPv6 port-22 hosts
         * unbracketed (migration tolerance).
         */
        fun matchesHost(entryHost: String, hostname: String, port: Int): Boolean {
            val canonical = hostField(hostname, port)
            if (entryHost == canonical) return true
            val bare = hostname.removePrefix("[").removeSuffix("]")
            if (bare.contains(":") && port == 22) {
                // legacy unbracketed IPv6 entry
                if (entryHost == bare) return true
            }
            return false
        }

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
            if (!isPlausibleKeyBlob(blob)) return null
            return Entry(parts[0], parts[1], blob)
        }

        /**
         * A known_hosts key blob must open with an ssh string naming a known
         * key algorithm; OpenSSH ignores lines whose key it cannot parse, so
         * garbage blobs (truncated base64, arbitrary data) must not create
         * entries — otherwise they'd read as changed keys instead of being
         * skipped.
         */
        fun isPlausibleKeyBlob(blob: ByteArray): Boolean {
            if (blob.size < 4) return false
            val len = ((blob[0].toInt() and 0xFF) shl 24) or ((blob[1].toInt() and 0xFF) shl 16) or
                ((blob[2].toInt() and 0xFF) shl 8) or (blob[3].toInt() and 0xFF)
            if (len < 4 || len > blob.size - 4) return false
            val alg = String(blob, 4, len, Charsets.US_ASCII)
            return (
                alg.startsWith("ssh-") || alg.startsWith("ecdsa-") ||
                    alg.startsWith("sk-ssh-") || alg.startsWith("x509v3-")
                ) &&
                alg.none { it.code < 32 || it.code > 126 }
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
            } catch (e: Throwable) {
                // a crashing prompt must not hang the handshake; truly fatal
                // VM errors still propagate after unblocking the wait
                future.complete(false)
                if (e.isFatalVmError()) throw e
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

/** Errors that must propagate even from a prompt wrapped in runCatching-style handling. */
private fun Throwable.isFatalVmError(): Boolean = this is VirtualMachineError || this is ThreadDeath
