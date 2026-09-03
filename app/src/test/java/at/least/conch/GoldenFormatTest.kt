package at.least.conch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Golden wire-format pins. These freeze the EXACT serialized shape of every
 * persistence surface (hosts.json, snippets.json, command_history.bin
 * plaintext, backup CONCHBAK plaintext, extra-keys prefs) so a serialization
 * library swap (org.json -> kotlinx.serialization) cannot drift the format.
 *
 * Canonicalization: JSON object key order and number spelling (18 vs 18.0)
 * carry no semantics for any consumer (Conch iOS parses the same backup with
 * a standard JSON parser), so goldens compare a CANONICAL form: keys sorted
 * recursively, arrays order-preserved, integral numbers normalized. Anything
 * that survives this canonicalization IS the wire contract.
 */
class GoldenFormatTest {

    // ------------------------------------------------------------ canonical

    private fun canon(json: String): String =
        canonicalValue(
            if (json.trim().startsWith("[")) JSONArray(json) else JSONObject(json)
        )

    private fun canonicalize(o: JSONObject): String {
        val sb = StringBuilder("{")
        val keys = o.keys().asSequence().toList().sorted()
        keys.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            sb.append(quote(k)).append(':').append(canonicalValue(o.get(k)))
        }
        return sb.append('}').toString()
    }

    private fun canonicalize(a: JSONArray): String {
        val sb = StringBuilder("[")
        for (i in 0 until a.length()) {
            if (i > 0) sb.append(',')
            sb.append(canonicalValue(a.get(i)))
        }
        return sb.append(']').toString()
    }

    private fun canonicalValue(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> canonicalize(v)
        is JSONArray -> canonicalize(v)
        is Boolean -> v.toString()
        is String -> quote(v)
        is Double -> num(if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toDouble() else v)
        is Float -> canonicalValue(v.toDouble())
        is Number -> num(v.toDouble())
        else -> throw IllegalArgumentException("unexpected json value: $v")
    }

    private fun num(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ---------------------------------------------------------------- hosts

    @Test
    fun `golden host json - every field populated`() {
        val host = Host(
            id = "golden-1",
            alias = "生產機 \"prod\"",
            hostname = "prod.example.com",
            port = 2222,
            username = "alice",
            authType = Host.AUTH_KEY,
            keyId = "key-a",
            fontSizeSp = 18f,
            keepAlive = false,
            tmuxAutoAttach = true,
            socksPort = 1080,
        )
        host.tunnels.add(Tunnel(8080, "db.internal", 5432))
        host.tunnels.add(Tunnel(1, "", 0))
        assertEquals(
            """{"alias":"生產機 \"prod\"","authType":"KEY","fontSizeSp":18,"hostname":"prod.example.com","id":"golden-1","keepAlive":false,"keyId":"key-a","port":2222,"socksPort":1080,"tmuxAutoAttach":true,"tunnels":[{"host":"db.internal","localPort":8080,"port":5432},{"host":"","localPort":1,"port":0}],"username":"alice"}""",
            canon(ConchJson.encodeToString(HostWire.serializer(), HostWire.from(host))),
        )
    }

    @Test
    fun `golden host json - in-memory defaults and explicit null keyId`() {
        // Host() constructed in code: tmux default OFF, keyId null -> null literal
        val host = Host(id = "golden-2", hostname = "h", username = "u")
        assertEquals(
            """{"alias":"","authType":"PASSWORD","fontSizeSp":0,"hostname":"h","id":"golden-2","keepAlive":true,"keyId":null,"port":22,"socksPort":0,"tmuxAutoAttach":false,"tunnels":[],"username":"u"}""",
            canon(ConchJson.encodeToString(HostWire.serializer(), HostWire.from(host))),
        )
    }

    @Test
    @Suppress("MaxLineLength")
    fun `golden host json - group bindHost omitted at default, written when set`() {
        // Shared-format fields (docs/backup-format.md): absent == default,
        // so the goldens above stay byte-identical for hosts without them.
        val host = Host(id = "g-1", hostname = "h", username = "u")
        val off = canon(ConchJson.encodeToString(HostWire.serializer(), HostWire.from(host)))
        assertFalse(off.contains("group"))
        host.group = " Prod "
        host.tunnels.add(Tunnel(9000, "127.0.0.1", 9001, remote = true, bindHost = "0.0.0.0"))
        // a LOCAL tunnel never carries bindHost, whatever the object holds
        host.tunnels.add(Tunnel(8080, "db", 5432, remote = false, bindHost = "ignored"))
        assertEquals(
            """{"alias":"","authType":"PASSWORD","fontSizeSp":0,"group":"Prod","hostname":"h","id":"g-1","keepAlive":true,"keyId":null,"port":22,"socksPort":0,"tmuxAutoAttach":false,"tunnels":[{"bindHost":"0.0.0.0","host":"127.0.0.1","localPort":9000,"port":9001,"remote":true},{"host":"db","localPort":8080,"port":5432}],"username":"u"}""",
            canon(ConchJson.encodeToString(HostWire.serializer(), HostWire.from(host))),
        )
    }

    @Test
    @Suppress("MaxLineLength")
    fun `golden host decode - ios-written tunnel with redundant direction decodes by remote flag`() {
        val back = ConchJson.decodeFromString(
            HostWire.serializer(),
            // knockPorts is a REMOVED field (feature deleted 2026-09); it stays
            // in this fixture to pin that old on-disk hosts.json still decodes.
            """{"id":"x","hostname":"h","username":"u","group":"G","knockPorts":[1,70000,2],"tunnels":[{"direction":"REMOTE","remote":true,"localPort":9000,"host":"127.0.0.1","port":9001,"bindHost":"0.0.0.0"},{"direction":"LOCAL","localPort":1,"host":"a","port":2}]}""",
        ).toHost()
        assertEquals("G", back.group)
        assertEquals(
            listOf(Tunnel(9000, "127.0.0.1", 9001, remote = true, bindHost = "0.0.0.0"), Tunnel(1, "a", 2)),
            back.tunnels,
        )
    }

    @Test
    @Suppress("MaxLineLength")
    fun `golden key decode - fractional createdAt is truncated, integer is written`() {
        val k = ConchJson.decodeFromString(
            KeyWire.serializer(),
            """{"id":"k","name":"n","algorithm":"ssh-ed25519","createdAt":1735689600123.456,"publicLine":"p","fingerprint":"f"}""",
        )
        assertEquals(1735689600123L, k.createdAt)
        assertTrue(ConchJson.encodeToString(KeyWire.serializer(), k).contains("\"createdAt\":1735689600123"))
        assertEquals(
            7L,
            ConchJson.decodeFromString(
                KeyWire.serializer(),
                """{"id":"k","name":"n","algorithm":"a","createdAt":7,"publicLine":"p","fingerprint":"f"}""",
            ).createdAt,
        )
    }

    @Test
    fun `golden host decode - decode fallback defaults differ from in-memory defaults`() {
        // THE tmux trap: absent field decodes to FALSE (pre-feature backups)
        // and Host() in memory now also defaults to FALSE (2026-09 default
        // change; saved hosts carry their own explicit value). Pinned by
        // HostStoreJsonTest and re-pinned here at the raw-string level for
        // the library swap.
        val back = ConchJson.decodeFromString(
            HostWire.serializer(),
            """{"id":"x","hostname":"h","username":"u","authType":"GARBAGE","keyId":null}"""
        ).toHost()
        assertEquals(Host.AUTH_PASSWORD, back.authType)
        assertNull(back.keyId)
        assertEquals(false, back.tmuxAutoAttach)
        assertEquals(22, back.port)
        assertEquals(true, back.keepAlive)
    }

    // -------------------------------------------------------------- snippets

    @Test
    fun `golden snippet file json`() {
        val dir = Files.createTempDirectory("golden-snippets")
        val file = File(dir.toFile(), "snippets.json")
        SnippetStore(file).save(
            listOf(
                Snippet(id = "s1", label = "磁碟", command = "df -h"),
                Snippet(id = "s2", label = "q\"uote", command = "echo 'multi\nline'"),
            )
        )
        assertEquals(
            """[{"command":"df -h","id":"s1","label":"磁碟"},{"command":"echo 'multi\nline'","id":"s2","label":"q\"uote"}]""",
            canon(file.readText()),
        )
    }

    // --------------------------------------------------------------- history

    @Test
    fun `golden history json - exact string, array order preserved`() {
        val entries = listOf(
            HistoryEntry("h1", "uptime", 1000L),
            HistoryEntry("h2", "echo 'multi\nline'", 2000L),
            HistoryEntry("h1", "中文コマンド", 3000L),
        )
        assertEquals(
            """[{"hostId":"h1","text":"uptime","ts":1000},{"hostId":"h2","text":"echo 'multi\nline'","ts":2000},{"hostId":"h1","text":"中文コマンド","ts":3000}]""",
            CommandHistoryStore.historyToJson(entries),
        )
    }

    @Test
    fun `golden history decode - lenient opt-string fallbacks`() {
        val decoded = CommandHistoryStore.historyFromJson(
            """[{"hostId":"h1","text":"t","ts":5},{"text":"only-text"}]"""
        )
        assertEquals(listOf(HistoryEntry("h1", "t", 5L), HistoryEntry("", "only-text", 0L)), decoded)
    }

    // ------------------------------------------------------------ extra keys

    @Test
    fun `golden extra-keys json - exact string`() {
        assertEquals(
            """["CTRL","ESC","TAB","LEFT"]""",
            ExtraKeysConfig.serialize(listOf("CTRL", "ESC", "TAB", "LEFT")),
        )
    }

    // ---------------------------------------------------------------- backup

    @Test
    @Suppress("MaxLineLength")
    fun `golden backup payload json is canonical`() {
        // Model → BackupSchema → canonical JSON. Pins the MAPPING (tunnels
        // → forwards incl. the dynamic one, socksPort, secrets embedded,
        // fontSize omitted at 0, optionals absent) and the canonical form
        // (sorted keys, no whitespace, raw unicode). Cross-platform pins
        // live in CrossPlatformBackupFixtureTest.
        val host = Host(
            id = "h1", alias = "prod", hostname = "prod.example.com", port = 2222,
            username = "alice", authType = Host.AUTH_KEY, keyId = "k1",
            fontSizeSp = 14.5f, keepAlive = false, tmuxAutoAttach = false, socksPort = 1080,
        )
        host.tunnels.add(Tunnel(8080, "db.internal", 5432))
        host.tunnels.add(Tunnel(9000, "127.0.0.1", 9001, remote = true, bindHost = "0.0.0.0"))
        val pwHost = Host(id = "h2", hostname = "b.example.com", username = "bob")
        val payload = BackupPayload(
            exportedAt = "2026-08-29T05:30:00Z",
            origin = BackupOrigin("android", "0.9.1"),
            hosts = listOf(BackupHost.from(host, null), BackupHost.from(pwHost, "s3cret-パスワード🔑")),
            keys = listOf(
                BackupKey.from(
                    SshKeyInfo(
                        id = "k1",
                        name = "my-phone",
                        algorithm = "ssh-ed25519",
                        createdAt = 1735689600123L,
                        publicLine = "ssh-ed25519 AAAA… my-phone",
                        fingerprint = "SHA256:xxx",
                    ),
                    "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n",
                ),
            ),
            snippets = listOf(BackupSnippet("s1", "disk", "df -h")),
            knownHosts = listOfNotNull(BackupKnownHost.parseLine("[prod.example.com]:2222 ssh-ed25519 $ED25519_BLOB")),
        )
        assertEquals(
            """{"exportedAt":"2026-08-29T05:30:00Z","hosts":[{"auth":{"keyId":"k1","method":"key"},"exposeFiles":false,"fontSize":14.5,"forwards":[{"listenPort":8080,"targetHost":"db.internal","targetPort":5432,"type":"local"},{"listenHost":"0.0.0.0","listenPort":9000,"targetHost":"127.0.0.1","targetPort":9001,"type":"remote"},{"listenPort":1080,"type":"dynamic"}],"group":"","hostname":"prod.example.com","id":"h1","keepAlive":false,"name":"prod","port":2222,"tmuxAutoAttach":false,"username":"alice"},{"auth":{"method":"password","password":"s3cret-パスワード🔑"},"exposeFiles":false,"forwards":[],"group":"","hostname":"b.example.com","id":"h2","keepAlive":true,"name":"","port":22,"tmuxAutoAttach":false,"username":"bob"}],"keys":[{"algorithm":"ssh-ed25519","createdAt":"2025-01-01T00:00:00.123Z","fingerprint":"SHA256:xxx","id":"k1","name":"my-phone","privateKey":"-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n","publicKey":"ssh-ed25519 AAAA… my-phone"}],"knownHosts":[{"algorithm":"ssh-ed25519","host":"prod.example.com","port":2222,"publicKey":"$ED25519_BLOB"}],"origin":{"appVersion":"0.9.1","platform":"android"},"snippets":[{"command":"df -h","id":"s1","label":"disk"}]}""",
            BackupCodec.payloadToJson(payload),
        )
    }

    private companion object {
        const val ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIB3z4kLp1o3Qy9Fh0mF4y2Nn1YQe4rZ1B3o5vE7d2mXU"
    }
}
