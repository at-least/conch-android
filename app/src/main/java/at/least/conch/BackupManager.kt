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
        val existingHosts = hostStore.load()
        val existingIds = existingHosts.map { it.id }.toSet()
        var hostsAdded = 0
        for (hj in payload.hosts) {
            val host = HostStore.hostFromJson(hj)
            if (host.id in existingIds) continue
            val pw = payload.hostSecrets[host.id]
            if (!pw.isNullOrEmpty()) {
                SecretsStore.put("host-pw:${host.id}", pw)
            }
            existingHosts.add(host)
            hostsAdded++
        }
        if (hostsAdded > 0) hostStore.save(existingHosts)

        // keys
        var keysAdded = 0
        if (payload.keys.isNotEmpty()) {
            val km = KeyManager(context)
            val existingKeys = km.list()
            val keyIds = existingKeys.map { it.id }.toSet()
            val metaFile = File(context.filesDir, "keys").apply { mkdirs() }
            val arr = org.json.JSONArray()
            for (kj in payload.keys) {
                val id = kj.optString("id")
                if (id.isEmpty() || id in keyIds) continue
                val pem = payload.keySecrets[id]
                if (pem.isNullOrEmpty()) continue   // a key without its private half is useless
                SecretsStore.put("key-priv:$id", pem)
                arr.put(kj)
                keysAdded++
            }
            if (keysAdded > 0) {
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
            val snippets = ss.load()
            val ids = snippets.map { it.id }.toSet()
            for (sj in payload.snippets) {
                val id = sj.optString("id")
                if (id.isEmpty() || id in ids) continue
                snippets.add(Snippet(id = id, label = sj.optString("label"), command = sj.optString("command")))
                snippetsAdded++
            }
            if (snippetsAdded > 0) ss.save(snippets)
        }

        // known_hosts: merge unique lines
        var merged = false
        if (payload.knownHosts.isNotBlank()) {
            val file = File(context.filesDir, "known_hosts")
            val current = if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
            val before = current.size
            current.addAll(payload.knownHosts.lines().filter { it.isNotBlank() })
            if (current.size > before) {
                file.writeText(current.joinToString("\n", postfix = "\n"))
                merged = true
            }
        }

        return RestoreResult(hostsAdded, keysAdded, snippetsAdded, merged)
    }
}
