package at.least.conch

import android.content.Context
import org.json.JSONObject
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
            incoming: List<JSONObject>,
            keySecrets: Map<String, String>,
        ): List<String> =
            incoming.map { it.optString("id") }
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
        val hostJsons = hosts.map { HostStore.hostToJson(it) }
        val hostSecrets = hosts.associate {
            it.id to (SecretsStore.get("host-pw:${it.id}") ?: "")
        }

        val keys = KeyManager(context).list()
        val keyJsons = keys.map {
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("algorithm", it.algorithm)
                .put("createdAt", it.createdAt)
                .put("publicLine", it.publicLine)
                .put("fingerprint", it.fingerprint)
        }
        val keySecrets = keys.associate {
            it.id to (SecretsStore.get("key-priv:${it.id}") ?: "")
        }

        val snippets = SnippetStore(context).load()
        val snippetJsons = snippets.map {
            JSONObject().put("id", it.id).put("label", it.label).put("command", it.command)
        }

        val knownHosts = runCatching {
            File(context.filesDir, "known_hosts").takeIf { it.exists() }?.readText() ?: ""
        }.getOrDefault("")

        return BackupCodec.BackupPayload(
            hosts = hostJsons,
            hostSecrets = hostSecrets,
            keys = keyJsons,
            keySecrets = keySecrets,
            snippets = snippetJsons,
            knownHosts = knownHosts,
        )
    }

    fun restore(payload: BackupCodec.BackupPayload): RestoreResult {
        // hosts
        val hostStore = HostStore(context)
        val incomingHosts = payload.hosts.map { HostStore.hostFromJson(it) }
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
                val metaFile = File(context.filesDir, "keys").apply { mkdirs() }
                val arr = org.json.JSONArray()
                val byId = payload.keys.associateBy { it.optString("id") }
                for (id in importIds) {
                    SecretsStore.put("key-priv:$id", payload.keySecrets[id]!!)
                    arr.put(byId[id])
                }
                for (existing in existingKeys) {
                    arr.put(
                        JSONObject()
                            .put("id", existing.id)
                            .put("name", existing.name)
                            .put("algorithm", existing.algorithm)
                            .put("createdAt", existing.createdAt)
                            .put("publicLine", existing.publicLine)
                            .put("fingerprint", existing.fingerprint)
                    )
                }
                File(metaFile, "keys.json").writeText(arr.toString())
            }
        }

        // snippets
        var snippetsAdded = 0
        if (payload.snippets.isNotEmpty()) {
            val ss = SnippetStore(context)
            val incomingSnippets = payload.snippets.map {
                Snippet(id = it.optString("id"), label = it.optString("label"), command = it.optString("command"))
            }
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
