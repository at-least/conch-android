package at.least.conch

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Collects app data into a [BackupCodec.BackupPayload] and restores it.
 * Merge semantics on restore: existing entries (same id) are kept, new ones
 * are appended — an import never destroys current data.
 */
class BackupManager(private val context: Context) {

    data class RestoreResult(
        val hostsAdded: Int,
        val keysAdded: Int,
        val snippetsAdded: Int,
        val knownHostsMerged: Boolean,
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
                if (h.id in existingIds) continue
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
                if (s.id in ids) continue
                out.add(s)
                added.add(s.id)
            }
            return out to added
        }

        /** Keys import: skip ids already known; a key without its private half is useless. */
        fun keyIdsToImport(
            existingIds: Set<String>,
            incoming: List<KeyWire>,
            keySecrets: Map<String, String>,
        ): List<String> =
            incoming.map { it.id }
                .filter { it.isNotEmpty() && it !in existingIds && !keySecrets[it].isNullOrEmpty() }

        /** known_hosts merge: dedup union of non-blank lines, first-seen order, + grew flag. */
        fun mergeKnownHostsLines(current: List<String>, incoming: List<String>): Pair<List<String>, Boolean> {
            val union = current.filter { it.isNotBlank() }.toMutableSet()
            val before = union.size
            union.addAll(incoming.filter { it.isNotBlank() })
            return union.toList() to (union.size > before)
        }
    }

    fun collect(): BackupCodec.BackupPayload {
        val hosts = HostStore(context).load()
        val hostSecrets = hosts.associate {
            it.id to (SecretsStore.get("host-pw:${it.id}") ?: "")
        }

        val keys = KeyManager(context).list()
        val keySecrets = keys.associate {
            it.id to (SecretsStore.get("key-priv:${it.id}") ?: "")
        }

        val snippets = SnippetStore(context).load()

        val knownHosts = runCatching {
            File(context.filesDir, "known_hosts").takeIf { it.exists() }?.readText() ?: ""
        }.getOrDefault("")

        return BackupCodec.BackupPayload(
            hosts = hosts.map { HostWire.from(it) },
            hostSecrets = hostSecrets,
            keys = keys.map { KeyWire.from(it) },
            keySecrets = keySecrets,
            snippets = snippets.map { SnippetWire.from(it) },
            knownHosts = knownHosts,
        )
    }

    fun restore(payload: BackupCodec.BackupPayload): RestoreResult {
        // hosts
        val hostStore = HostStore(context)
        val incomingHosts = payload.hosts.map { it.toHost() }
        val (mergedHosts, addedHostIds) = mergeHosts(hostStore.load(), incomingHosts)
        for (id in addedHostIds) {
            val pw = payload.hostSecrets[id]
            if (!pw.isNullOrEmpty()) {
                SecretsStore.put("host-pw:$id", pw)
            }
        }
        if (addedHostIds.isNotEmpty()) hostStore.save(mergedHosts)

        // keys
        var keysAdded = 0
        if (payload.keys.isNotEmpty()) {
            val km = KeyManager(context)
            val existingKeys = km.list()
            val importIds = keyIdsToImport(existingKeys.map { it.id }.toSet(), payload.keys, payload.keySecrets)
            keysAdded = importIds.size
            if (keysAdded > 0) {
                val merged = mutableListOf<KeyWire>()
                val byId = payload.keys.associateBy { it.id }
                for (id in importIds) {
                    SecretsStore.put("key-priv:$id", payload.keySecrets[id]!!)
                    merged.add(byId.getValue(id))
                }
                existingKeys.forEach { merged.add(KeyWire.from(it)) }
                val metaFile = File(context.filesDir, "keys").apply { mkdirs() }
                File(metaFile, "keys.json").writeText(
                    ConchJson.encodeToString(ListSerializer(KeyWire.serializer()), merged)
                )
            }
        }

        // snippets
        var snippetsAdded = 0
        if (payload.snippets.isNotEmpty()) {
            val ss = SnippetStore(context)
            val incomingSnippets = payload.snippets.map { it.toSnippet() }
            val (mergedSnippets, addedSnippetIds) = mergeSnippets(ss.load(), incomingSnippets)
            snippetsAdded = addedSnippetIds.size
            if (snippetsAdded > 0) ss.save(mergedSnippets)
        }

        // known_hosts: merge unique lines
        val file = File(context.filesDir, "known_hosts")
        val currentLines = if (file.exists()) file.readLines() else emptyList()
        val (union, grew) = mergeKnownHostsLines(currentLines, payload.knownHosts.lines())
        if (grew) {
            file.writeText(union.joinToString("\n", postfix = "\n"))
        }

        return RestoreResult(addedHostIds.size, keysAdded, snippetsAdded, grew)
    }
}
