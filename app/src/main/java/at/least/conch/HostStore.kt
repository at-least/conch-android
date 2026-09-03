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
    /**
     * Remote (-R) only: address the SERVER binds the listen port to
     * ("" = loopback, "0.0.0.0" = all interfaces; needs GatewayPorts on the
     * server). Shared wire field with iOS.
     */
    val bindHost: String = "",
)

@Serializable
data class TunnelWire(
    val localPort: Int = 0,
    val host: String = "",
    val port: Int = 0,
    /** Remote (-R) forward; omitted when false for byte-compatible JSON. */
    @EncodeDefault(Mode.NEVER) val remote: Boolean = false,
    /**
     * Remote (-R) server-side bind address; omitted when empty
     * (docs/backup-format.md). iOS additionally writes a redundant
     * `direction` ("LOCAL"/"REMOTE") next to `remote`; `remote` is the
     * authoritative flag and `direction` is skipped as an unknown key.
     */
    @EncodeDefault(Mode.NEVER) val bindHost: String = "",
) {
    fun toTunnel() = Tunnel(localPort, host, port, remote, bindHost)

    companion object {
        fun from(t: Tunnel) = TunnelWire(
            localPort = t.localPort,
            host = t.host,
            port = t.port,
            remote = t.remote,
            bindHost = if (t.remote) t.bindHost else "",
        )
    }
}

/**
 * Wire shape of a host entry in hosts.json (private to this app; the backup
 * format maps through BackupSchema).
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
    /** Expose this host to system file pickers via the SAF DocumentsProvider. */
    @EncodeDefault(Mode.NEVER) val safExpose: Boolean = false,
    /** Host-list group ("" = ungrouped). Shared with iOS; omitted when empty. */
    @EncodeDefault(Mode.NEVER) val group: String = "",
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
            safExpose = safExpose,
            group = group.trim(),
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
            safExpose = h.safExpose,
            group = h.group.trim(),
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
    // Default OFF for newly created hosts. HostWire decodes its own fallback
    // of false so pre-feature backups and saved hosts stay exactly as they
    // were.
    var tmuxAutoAttach: Boolean = false,
    var socksPort: Int = 0, // local SOCKS5 proxy (0 = off)
    /** ProxyJump: id of another saved host to tunnel this connection through (null = direct). */
    var jumpHostId: String? = null,
    /** Expose this host's files to system file pickers (SAF DocumentsProvider). */
    var safExpose: Boolean = false,
    /** Optional host-list group; blank = the ungrouped section (see [HostGrouping]). */
    var group: String = "",
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
        var migrationFailed = false
        if (file.exists()) {
            try {
                val wires = ConchJson.decodeFromString(ListSerializer(HostWire.serializer()), file.readText())
                for (w in wires) {
                    val host = w.toHost()
                    // the host is kept whether or not its legacy password
                    // moves: a keystore hiccup must neither drop the entry
                    // nor (below) rewrite the file without it
                    list.add(host)
                    try {
                        if (migrateLegacyPassword(w, host)) migrated = true
                    } catch (_: Exception) {
                        migrationFailed = true
                    }
                }
            } catch (_: Exception) {
                // keep a copy for recovery: the next save would otherwise
                // overwrite the corrupt-but-maybe-salvageable file
                preserveCorrupt()
            }
        }
        // Re-save (which strips the plaintext field) only once EVERY legacy
        // password made it into the keystore; otherwise the file keeps the
        // password and the migration simply runs again on the next load.
        if (migrated && !migrationFailed) {
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
