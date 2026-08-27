package at.least.conch

import android.content.Context
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

data class Tunnel(
    /** Local tunnel: phone listens here. Remote (-R): SERVER listens here. */
    val localPort: Int,
    /** Local: remote-side target. Remote: phone-side target (resolved on this device). */
    val host: String,
    val port: Int,
    val remote: Boolean = false,
)

@Serializable
data class TunnelWire(
    val localPort: Int = 0,
    val host: String = "",
    val port: Int = 0,
    /** Remote (-R) forward; omitted when false for byte-compatible JSON. */
    @EncodeDefault(Mode.NEVER) val remote: Boolean = false,
) {
    fun toTunnel() = Tunnel(localPort, host, port, remote)

    companion object {
        fun from(t: Tunnel) = TunnelWire(t.localPort, t.host, t.port, t.remote)
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
    /** Other host id to connect through (ProxyJump). Omitted when null so the JSON stays byte-compatible. */
    @EncodeDefault(Mode.NEVER) val jumpHostId: String? = null,
    @EncodeDefault(Mode.NEVER) val password: String? = null,
    /** ssh-agent forwarding; omitted when false for byte-compatible backups. */
    @EncodeDefault(Mode.NEVER) val forwardAgent: Boolean = false,
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
            jumpHostId = jumpHostId,
            forwardAgent = forwardAgent,
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
            jumpHostId = h.jumpHostId,
            forwardAgent = h.forwardAgent,
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
    /** ProxyJump: id of another saved host to tunnel this connection through (null = direct). */
    var jumpHostId: String? = null,
    /** ssh-agent forwarding (-A): the server may ask this device to sign with stored keys. */
    var forwardAgent: Boolean = false,
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
        if (file.exists()) {
            try {
                val wires = ConchJson.decodeFromString(ListSerializer(HostWire.serializer()), file.readText())
                for (w in wires) {
                    try {
                        val host = w.toHost()
                        if (migrateLegacyPassword(w, host)) migrated = true
                        list.add(host)
                    } catch (_: Exception) {
                        // one unreadable entry (keystore hiccup during the
                        // legacy migration) must not drop the whole tail of
                        // the list — the next save would persist the loss
                    }
                }
            } catch (_: Exception) {
                // keep a copy for recovery: the next save would otherwise
                // overwrite the corrupt-but-maybe-salvageable file
                preserveCorrupt()
            }
        }
        if (migrated) {
            runCatching { save(list) }
        }
        return list
    }

    private fun preserveCorrupt() {
        if (!file.exists()) return
        runCatching {
            file.copyTo(File(file.parentFile, "${file.name}.corrupt"), overwrite = true)
        }
    }

    /** Moves a legacy plaintext password from hosts.json into the Keystore vault. */
    private fun migrateLegacyPassword(w: HostWire, host: Host): Boolean {
        if (w.password.isNullOrEmpty()) return false
        if (SecretsStore.get("host-pw:${host.id}") == null) {
            SecretsStore.put("host-pw:${host.id}", w.password)
        }
        return true
    }

    fun save(hosts: List<Host>) {
        val arr = hosts.map { HostWire.from(it) }
        AtomicFile.write(file, ConchJson.encodeToString(ListSerializer(HostWire.serializer()), arr))
    }

    fun deleteSecrets(hostId: String) {
        SecretsStore.delete("host-pw:$hostId")
    }
}
