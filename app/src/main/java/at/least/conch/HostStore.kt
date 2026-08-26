package at.least.conch

import android.content.Context
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

data class Tunnel(
    val localPort: Int,
    val host: String,
    val port: Int,
)

@Serializable
data class TunnelWire(
    val localPort: Int = 0,
    val host: String = "",
    val port: Int = 0,
) {
    fun toTunnel() = Tunnel(localPort, host, port)

    companion object {
        fun from(t: Tunnel) = TunnelWire(t.localPort, t.host, t.port)
    }
}

/**
 * Wire shape of a host entry in hosts.json and the TILDBAK1 backup payload.
 * Decode defaults mirror the original org.json opt* fallbacks — in
 * particular tmuxAutoAttach decodes to FALSE (pre-feature backups stay off)
 * while the in-memory [Host] defaults new hosts to true. keyId null is
 * written as an explicit JSON null (org.json JSONObject.NULL semantics).
 */
@Serializable
data class HostWire(
    val id: String? = null,
    val alias: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: String = Host.AUTH_PASSWORD,
    val keyId: String? = null,
    val fontSizeSp: Double = 0.0,
    val keepAlive: Boolean = true,
    val tmuxAutoAttach: Boolean = false,
    val socksPort: Int = 0,
    val tunnels: List<TunnelWire> = emptyList(),
    @EncodeDefault(Mode.NEVER) val password: String? = null,
) {
    fun toHost(): Host {
        val host = Host(
            id = id ?: UUID.randomUUID().toString(),
            alias = alias,
            hostname = hostname,
            port = port,
            username = username,
            authType = if (authType == Host.AUTH_KEY) Host.AUTH_KEY else Host.AUTH_PASSWORD,
            keyId = keyId,
            fontSizeSp = fontSizeSp.toFloat(),
            keepAlive = keepAlive,
            tmuxAutoAttach = tmuxAutoAttach,
            socksPort = socksPort,
        )
        host.tunnels.addAll(tunnels.map { it.toTunnel() })
        return host
    }

    companion object {
        fun from(h: Host) = HostWire(
            id = h.id,
            alias = h.alias,
            hostname = h.hostname,
            port = h.port,
            username = h.username,
            authType = h.authType,
            keyId = h.keyId,
            fontSizeSp = h.fontSizeSp.toDouble(),
            keepAlive = h.keepAlive,
            tmuxAutoAttach = h.tmuxAutoAttach,
            socksPort = h.socksPort,
            tunnels = h.tunnels.map { TunnelWire.from(it) },
        )
    }
}

data class Host(
    val id: String = UUID.randomUUID().toString(),
    var alias: String = "",
    var hostname: String = "",
    var port: Int = 22,
    var username: String = "",
    var authType: String = AUTH_PASSWORD, // AUTH_PASSWORD | AUTH_KEY
    var keyId: String? = null,
    var fontSizeSp: Float = 0f, // 0 = app default
    var keepAlive: Boolean = true,
    // Default ON for newly created hosts (mobile networks drop; tmux keeps
    // the session alive server-side). HostWire decodes its own fallback of
    // false so pre-feature backups and saved hosts stay exactly as they were.
    var tmuxAutoAttach: Boolean = true,
    var socksPort: Int = 0, // local SOCKS5 proxy (0 = off)
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
                val wires = ConchJson.decodeFromString(ListSerializer(HostWire.serializer()), file.readText())
                for (w in wires) {
                    val host = w.toHost()
                    if (!w.password.isNullOrEmpty()) {
                        if (SecretsStore.get("host-pw:${host.id}") == null) {
                            SecretsStore.put("host-pw:${host.id}", w.password)
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
        val arr = hosts.map { HostWire.from(it) }
        file.writeText(ConchJson.encodeToString(ListSerializer(HostWire.serializer()), arr))
    }

    fun deleteSecrets(hostId: String) {
        SecretsStore.delete("host-pw:$hostId")
    }
}
