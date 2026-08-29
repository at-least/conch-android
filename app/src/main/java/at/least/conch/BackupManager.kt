package at.least.conch

import android.content.Context
import java.io.File

/**
 * Collects app data into a [BackupPayload] and restores it.
 * Merge semantics on restore: existing entries (same id) are kept, new ones
 * are appended — an import never destroys current data.
 */
class BackupManager(private val context: Context) {

    data class RestoreResult(
        val hostsAdded: Int,
        val keysAdded: Int,
        val snippetsAdded: Int,
        val knownHostsMerged: Boolean,
        /**
         * Secrets written for ids that already existed but had no readable
         * secret — the Keystore-reset recovery path (see [restore]).
         */
        val secretsRefilled: Int = 0,
        /** Keys the backup carried without their private half (spec §2.2). */
        val keysSkipped: Int = 0,
    )

    companion object {
        /**
         * Merge semantics (iOS parity): an import never destroys current
         * data — existing ids are kept VERBATIM, only new ids append.
         * Pure so the decisions are unit-testable on the JVM.
         */
        fun mergeHosts(existing: List<Host>, incoming: List<Host>): Pair<List<Host>, List<String>> {
            val existingIds = existing.map { it.id }.toSet()
            val added = mutableListOf<String>()
            val out = existing.toMutableList()
            for (h in incoming) {
                if (h.id.isBlank() || h.id in existingIds) continue
                out.add(h)
                added.add(h.id)
            }
            return out to added
        }

        fun mergeSnippets(existing: List<Snippet>, incoming: List<Snippet>): Pair<List<Snippet>, List<String>> {
            val ids = existing.map { it.id }.toSet()
            val added = mutableListOf<String>()
            val out = existing.toMutableList()
            for (s in incoming) {
                if (s.id.isBlank() || s.id in ids) continue
                out.add(s)
                added.add(s.id)
            }
            return out to added
        }

        /** Keys import: skip ids already known; a key without its private half is useless. */
        fun keyIdsToImport(existingIds: Set<String>, incoming: List<BackupKey>): List<String> =
            incoming
                .filter { it.id.isNotBlank() && it.id !in existingIds && !it.privateKey.isNullOrEmpty() }
                .map { it.id }

        /** known_hosts merge: dedup union of non-blank lines, first-seen order, + grew flag. */
        fun mergeKnownHostsLines(current: List<String>, incoming: List<String>): Pair<List<String>, Boolean> {
            val union = current.filter { it.isNotBlank() }.toMutableSet()
            val before = union.size
            union.addAll(incoming.filter { it.isNotBlank() })
            return union.toList() to (union.size > before)
        }
    }

    /**
     * True when some stored secret exists but cannot be decrypted right now
     * (Keystore reset or hiccup). [collect] would then omit every such
     * secret — and a scheduled export would overwrite the user's only
     * off-device copy of their private keys with a hollow backup.
     */
    fun hasUnreadableSecrets(): Boolean {
        val aliases = HostStore(context).load().map { "host-pw:${it.id}" } +
            KeyManager(context).list().map { "key-priv:${it.id}" }
        return aliases.any { SecretsStore.contains(it) && SecretsStore.get(it) == null }
    }

    fun collect(nowMs: Long = System.currentTimeMillis()): BackupPayload {
        val hosts = HostStore(context).load().map { BackupHost.from(it, SecretsStore.get("host-pw:${it.id}")) }
        val keys = KeyManager(context).list().map { BackupKey.from(it, SecretsStore.get("key-priv:${it.id}")) }
        val snippets = SnippetStore(context).load().map { BackupSnippet.from(it) }
        val knownHosts = runCatching {
            File(context.filesDir, "known_hosts").takeIf { it.exists() }?.readLines() ?: emptyList()
        }.getOrDefault(emptyList()).mapNotNull { BackupKnownHost.parseLine(it) }

        return BackupPayload(
            exportedAt = BackupTime.formatSeconds(nowMs),
            origin = BackupOrigin(platform = "android", appVersion = BuildConfig.VERSION_NAME),
            hosts = hosts,
            keys = keys,
            snippets = snippets,
            knownHosts = knownHosts,
        )
    }

    /**
     * Keystore-reset recovery: the ids are all still on disk, so the merge
     * adds nothing — yet their secrets are gone. Writing a backup's secret
     * over an UNREADABLE one destroys nothing readable, so merge semantics
     * hold. Returns how many were refilled.
     */
    private fun refillSecrets(ids: List<String>, prefix: String, secrets: Map<String, String?>): Int {
        var n = 0
        for (id in ids) {
            val secret = secrets[id]
            if (!secret.isNullOrEmpty() && SecretsStore.get("$prefix$id") == null) {
                SecretsStore.put("$prefix$id", secret)
                n++
            }
        }
        return n
    }

    fun restore(payload: BackupPayload): RestoreResult {
        // hosts
        val hostStore = HostStore(context)
        val incomingHosts = payload.hosts.map { it.toHost() }
        val passwords = payload.hosts.associate { it.id to it.password }
        val (mergedHosts, addedHostIds) = mergeHosts(hostStore.load(), incomingHosts)
        for (id in addedHostIds) {
            passwords[id]?.let { SecretsStore.put("host-pw:$id", it) }
        }
        if (addedHostIds.isNotEmpty()) hostStore.save(mergedHosts)
        var refilled = refillSecrets(
            incomingHosts.map { it.id }.filter { it !in addedHostIds },
            "host-pw:",
            passwords,
        )

        // keys
        var keysAdded = 0
        var keysSkipped = 0
        if (payload.keys.isNotEmpty()) {
            val km = KeyManager(context)
            val existingKeys = km.list()
            // an unreadable keys.json reads as "no keys"; merging into that
            // would rewrite the file with only the imported ones
            check(!km.metaUnreadable) { KeyManager.UNREADABLE_META }
            val existingIds = existingKeys.map { it.id }.toSet()
            val importIds = keyIdsToImport(existingIds, payload.keys)
            keysAdded = importIds.size
            keysSkipped = payload.keys.count {
                it.id.isNotBlank() && it.id !in existingIds && it.privateKey.isNullOrEmpty()
            }
            if (keysAdded > 0) {
                val byId = payload.keys.associateBy { it.id }
                val merged = mutableListOf<SshKeyInfo>()
                for (id in importIds) {
                    val key = byId.getValue(id)
                    SecretsStore.put("key-priv:$id", key.privateKey!!)
                    merged.add(key.toInfo())
                }
                merged.addAll(existingKeys)
                // through KeyManager so the write is atomic and the format
                // has a single owner
                km.save(merged)
            }
            refilled += refillSecrets(
                existingKeys.map { it.id },
                "key-priv:",
                payload.keys.associate { it.id to it.privateKey },
            )
        }

        // snippets
        var snippetsAdded = 0
        if (payload.snippets.isNotEmpty()) {
            val ss = SnippetStore(context)
            val (mergedSnippets, addedSnippetIds) = mergeSnippets(ss.load(), payload.snippets.map { it.toSnippet() })
            snippetsAdded = addedSnippetIds.size
            if (snippetsAdded > 0) ss.save(mergedSnippets)
        }

        // known_hosts: merge unique lines
        val file = File(context.filesDir, "known_hosts")
        val currentLines = if (file.exists()) file.readLines() else emptyList()
        val incomingLines = payload.knownHosts.filter { it.isValid }.map { it.toLine() }
        val (union, grew) = mergeKnownHostsLines(currentLines, incomingLines)
        if (grew) {
            AtomicFile.write(file, union.joinToString("\n", postfix = "\n"))
        }

        return RestoreResult(addedHostIds.size, keysAdded, snippetsAdded, grew, refilled, keysSkipped)
    }
}
