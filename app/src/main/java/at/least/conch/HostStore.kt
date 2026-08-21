package at.least.conch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Tunnel(
    val localPort: Int,
    val host: String,
    val port: Int,
) {
    fun toJSON(): JSONObject = JSONObject()
        .put("localPort", localPort)
        .put("host", host)
        .put("port", port)

    companion object {
        fun fromJSON(o: JSONObject): Tunnel = Tunnel(
            localPort = o.optInt("localPort"),
            host = o.optString("host"),
            port = o.optInt("port"),
        )
    }
}

data class Host(
    val id: String = UUID.randomUUID().toString(),
    var alias: String = "",
    var hostname: String = "",
    var port: Int = 22,
    var username: String = "",
    var authType: String = AUTH_PASSWORD,   // AUTH_PASSWORD | AUTH_KEY
    var keyId: String? = null,
    var fontSizeSp: Float = 0f,             // 0 = app default
    var keepAlive: Boolean = true,
    // Default ON for newly created hosts (mobile networks drop; tmux keeps
    // the session alive server-side). hostFromJson keeps its own fallback of
    // false so pre-feature backups and saved hosts stay exactly as they were.
    var tmuxAutoAttach: Boolean = true,
    var socksPort: Int = 0,                 // local SOCKS5 proxy (0 = off)
    var tunnels: MutableList<Tunnel> = mutableListOf(),
) {
    companion object {
        const val AUTH_PASSWORD = "PASSWORD"
        const val AUTH_KEY = "KEY"
    }
}

/**
 * Persists the host list as JSON in the app's private storage.
 * Passwords are NOT stored here — they live in [SecretsStore] keyed by
 * "host-pw:<id>" (legacy plaintext passwords are migrated on load).
 */
class HostStore(context: Context) {
    private val file: File = File(context.filesDir, "hosts.json")

    fun load(): MutableList<Host> {
        val list = mutableListOf<Host>()
        var migrated = false
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val host = hostFromJson(o)
                    val legacyPw = o.optString("password", "")
                    if (legacyPw.isNotEmpty()) {
                        if (SecretsStore.get("host-pw:${host.id}") == null) {
                            SecretsStore.put("host-pw:${host.id}", legacyPw)
                        }
                        migrated = true
                    }
                    list.add(host)
                }
            }
        } catch (_: Exception) {
        }
        if (migrated) {
            runCatching { save(list) }
        }
        return list
    }

    fun save(hosts: List<Host>) {
        val arr = JSONArray()
        for (h in hosts) arr.put(hostToJson(h))
        file.writeText(arr.toString())
    }

    fun deleteSecrets(hostId: String) {
        SecretsStore.delete("host-pw:$hostId")
    }

    companion object {
        fun hostFromJson(o: JSONObject): Host {
            val host = Host(
                id = o.optString("id", UUID.randomUUID().toString()),
                alias = o.optString("alias"),
                hostname = o.optString("hostname"),
                port = o.optInt("port", 22),
                username = o.optString("username"),
                authType = if (o.optString("authType") == Host.AUTH_KEY) Host.AUTH_KEY else Host.AUTH_PASSWORD,
                keyId = if (o.has("keyId") && !o.isNull("keyId")) o.optString("keyId") else null,
                fontSizeSp = o.optDouble("fontSizeSp", 0.0).toFloat(),
                keepAlive = o.optBoolean("keepAlive", true),
                tmuxAutoAttach = o.optBoolean("tmuxAutoAttach", false),
                socksPort = o.optInt("socksPort", 0),
            )
            val tunnels = o.optJSONArray("tunnels")
            if (tunnels != null) {
                for (t in 0 until tunnels.length()) {
                    host.tunnels.add(Tunnel.fromJSON(tunnels.getJSONObject(t)))
                }
            }
            return host
        }

        fun hostToJson(h: Host): JSONObject = JSONObject()
            .put("id", h.id)
            .put("alias", h.alias)
            .put("hostname", h.hostname)
            .put("port", h.port)
            .put("username", h.username)
            .put("authType", h.authType)
            .put("keyId", h.keyId ?: JSONObject.NULL)
            .put("fontSizeSp", h.fontSizeSp.toDouble())
            .put("keepAlive", h.keepAlive)
            .put("tmuxAutoAttach", h.tmuxAutoAttach)
            .put("socksPort", h.socksPort)
            .put("tunnels", JSONArray().apply { h.tunnels.forEach { put(it.toJSON()) } })
    }
}
