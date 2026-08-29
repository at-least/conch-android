package at.least.conch

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Wire types of the CONCHBAK backup payload — docs/backup-format.md is the
 * spec, shared with conch-ios. These are deliberately NOT the on-disk
 * `HostWire` / `KeyWire` shapes: the backup describes SSH concepts
 * (auth, forwards, known hosts) and each app maps its own model onto it
 * here, at the boundary.
 *
 * Nullable fields are *optional* in the spec: absent when null (the codec's
 * Json has explicitNulls = false), never written as `null`.
 */

@Serializable
data class BackupOrigin(
    val platform: String = "",
    val appVersion: String = "",
)

@Serializable
data class BackupAuth(
    val method: String = METHOD_PASSWORD,
    /** `method == password` only; absent = not stored, prompt at connect. */
    val password: String? = null,
    /** `method == key` only. */
    val keyId: String? = null,
) {
    companion object {
        const val METHOD_PASSWORD = "password"
        const val METHOD_KEY = "key"
    }
}

/** One `ssh -L` / `-R` / `-D` rule; `type` selects which fields apply. */
@Serializable
data class BackupForward(
    val type: String = "",
    /** remote only: server bind address ("" = loopback). */
    val listenHost: String? = null,
    val listenPort: Int = 0,
    val targetHost: String? = null,
    val targetPort: Int? = null,
) {
    companion object {
        const val TYPE_LOCAL = "local"
        const val TYPE_REMOTE = "remote"
        const val TYPE_DYNAMIC = "dynamic"

        fun local(listenPort: Int, targetHost: String, targetPort: Int) =
            BackupForward(TYPE_LOCAL, null, listenPort, targetHost, targetPort)

        fun remote(listenHost: String, listenPort: Int, targetHost: String, targetPort: Int) =
            BackupForward(TYPE_REMOTE, listenHost, listenPort, targetHost, targetPort)

        fun dynamic(listenPort: Int) = BackupForward(TYPE_DYNAMIC, null, listenPort)
    }
}

@Serializable
data class BackupHost(
    val id: String = "",
    val name: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    val group: String = "",
    val auth: BackupAuth = BackupAuth(),
    val jumpHostId: String? = null,
    val knockPorts: List<Int> = emptyList(),
    val forwards: List<BackupForward> = emptyList(),
    /** Terminal font size in points; absent = app default. */
    val fontSize: Double? = null,
    val keepAlive: Boolean = true,
    val tmuxAutoAttach: Boolean = true,
    val forwardAgent: Boolean = false,
    /** Android: SAF DocumentsProvider exposure (`Host.safExpose`). */
    val exposeFiles: Boolean = false,
) {
    val password: String? get() = auth.password?.takeIf { it.isNotEmpty() }

    fun toHost(): Host {
        val host = Host(
            id = id,
            alias = name,
            hostname = hostname,
            port = port,
            username = username,
            authType = if (auth.method == BackupAuth.METHOD_KEY) Host.AUTH_KEY else Host.AUTH_PASSWORD,
            keyId = auth.keyId?.takeIf { auth.method == BackupAuth.METHOD_KEY && it.isNotEmpty() },
            fontSizeSp = fontSize?.toFloat()?.takeIf { it > 0f } ?: 0f,
            keepAlive = keepAlive,
            tmuxAutoAttach = tmuxAutoAttach,
            socksPort = forwards.firstOrNull { it.type == BackupForward.TYPE_DYNAMIC }?.listenPort ?: 0,
            jumpHostId = jumpHostId?.takeIf { it.isNotEmpty() },
            forwardAgent = forwardAgent,
            safExpose = exposeFiles,
            group = group.trim(),
            knockPorts = knockPorts.filter(PortKnocker::isValidPort),
        )
        for (f in forwards) {
            when (f.type) {
                BackupForward.TYPE_LOCAL -> host.tunnels.add(
                    Tunnel(f.listenPort, f.targetHost ?: "", f.targetPort ?: 0),
                )
                BackupForward.TYPE_REMOTE -> host.tunnels.add(
                    Tunnel(
                        localPort = f.listenPort,
                        host = f.targetHost ?: "",
                        port = f.targetPort ?: 0,
                        remote = true,
                        bindHost = f.listenHost ?: "",
                    ),
                )
                // dynamic handled above; unknown types ignored (forward compat)
            }
        }
        return host
    }

    companion object {
        fun from(h: Host, password: String?): BackupHost {
            val forwards = h.tunnels.map { t ->
                if (t.remote) {
                    BackupForward.remote(t.bindHost, t.localPort, t.host, t.port)
                } else {
                    BackupForward.local(t.localPort, t.host, t.port)
                }
            } + listOfNotNull(h.socksPort.takeIf { it > 0 }?.let { BackupForward.dynamic(it) })
            val auth = if (h.authType == Host.AUTH_KEY) {
                BackupAuth(BackupAuth.METHOD_KEY, keyId = h.keyId)
            } else {
                BackupAuth(BackupAuth.METHOD_PASSWORD, password = password?.takeIf { it.isNotEmpty() })
            }
            return BackupHost(
                id = h.id,
                name = h.alias,
                hostname = h.hostname,
                port = h.port,
                username = h.username,
                group = h.group.trim(),
                auth = auth,
                jumpHostId = h.jumpHostId,
                knockPorts = h.knockPorts,
                forwards = forwards,
                fontSize = h.fontSizeSp.toDouble().takeIf { it > 0.0 },
                keepAlive = h.keepAlive,
                tmuxAutoAttach = h.tmuxAutoAttach,
                forwardAgent = h.forwardAgent,
                exposeFiles = h.safExpose,
            )
        }
    }
}

@Serializable
data class BackupKey(
    val id: String = "",
    val name: String = "",
    val algorithm: String = "",
    val createdAt: String = BackupTime.EPOCH,
    val publicKey: String = "",
    val fingerprint: String = "",
    /** PEM, unencrypted (the container is the encryption). Absent = unusable key. */
    val privateKey: String? = null,
) {
    fun toInfo() = SshKeyInfo(
        id = id,
        name = name,
        algorithm = algorithm,
        createdAt = BackupTime.parseMillis(createdAt) ?: 0L,
        publicLine = publicKey,
        fingerprint = fingerprint,
    )

    companion object {
        fun from(k: SshKeyInfo, privateKey: String?) = BackupKey(
            id = k.id,
            name = k.name,
            algorithm = k.algorithm,
            createdAt = BackupTime.formatMillis(k.createdAt),
            publicKey = k.publicLine,
            fingerprint = k.fingerprint,
            privateKey = privateKey?.takeIf { it.isNotEmpty() },
        )
    }
}

@Serializable
data class BackupSnippet(
    val id: String = "",
    val label: String = "",
    val command: String = "",
) {
    fun toSnippet() = Snippet(id, label, command)

    companion object {
        fun from(s: Snippet) = BackupSnippet(s.id, s.label, s.command)
    }
}

/** A trusted (or revoked) server key; structured so endpoint spelling never differs between writers. */
@Serializable
data class BackupKnownHost(
    val host: String = "",
    val port: Int = 22,
    val algorithm: String = "",
    /** Base64 of the SSH wire blob. */
    val publicKey: String = "",
    /** "revoked" | "cert-authority"; absent = plain trusted key. */
    val marker: String? = null,
) {
    val isValid: Boolean
        get() = host.isNotBlank() && algorithm.isNotBlank() &&
            runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull()
                ?.let(KnownHostsStore::isPlausibleKeyBlob) == true

    /** OpenSSH known_hosts line, the app's on-disk form. */
    fun toLine(): String {
        val prefix = marker?.takeIf { it.isNotBlank() }?.let { "@$it " } ?: ""
        return "$prefix${KnownHostsStore.hostField(host, port)} $algorithm $publicKey"
    }

    companion object {
        /** Parses one known_hosts line; null for comments, hashed/wildcard or garbage entries. */
        fun parseLine(line: String): BackupKnownHost? {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var i = 0
            var marker: String? = null
            if (parts.getOrNull(i)?.startsWith("@") == true) {
                marker = parts[i].drop(1)
                i++
            }
            if (parts.size < i + 3) return null
            val hostField = parts[i]
            if (hostField.startsWith("|") || hostField.contains('*') || hostField.contains('?')) return null
            val (host, port) = splitHostField(hostField)
            if (host.isEmpty()) return null
            val entry = BackupKnownHost(host, port, parts[i + 1], parts[i + 2], marker)
            return entry.takeIf { it.isValid }
        }

        /** `[host]:port` → (host, port); `[v6]` → (v6, 22); `host` → (host, 22). */
        fun splitHostField(field: String): Pair<String, Int> {
            if (field.startsWith("[")) {
                val close = field.indexOf(']')
                if (close < 0) return "" to 22
                val host = field.substring(1, close)
                val rest = field.substring(close + 1)
                val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: return "" to 22 else 22
                return host to port
            }
            return field to 22
        }
    }
}

@Serializable
data class BackupPayload(
    val exportedAt: String = BackupTime.EPOCH,
    val origin: BackupOrigin = BackupOrigin(),
    val hosts: List<BackupHost> = emptyList(),
    val keys: List<BackupKey> = emptyList(),
    val snippets: List<BackupSnippet> = emptyList(),
    val knownHosts: List<BackupKnownHost> = emptyList(),
)

/** RFC 3339 UTC timestamps as the spec writes them; lenient parsing of any RFC 3339 form. */
object BackupTime {
    const val EPOCH = "1970-01-01T00:00:00Z"

    /** Millisecond precision (`createdAt`); a whole-second value carries no fraction. */
    fun formatMillis(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    /** Whole seconds (`exportedAt`). */
    fun formatSeconds(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS))

    fun parseMillis(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
