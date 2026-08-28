package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared-format pins: the SAME two `.til` fixtures are checked into
 * conch-ios (`ConchTests/Fixtures/Backup/`) and decoded by its
 * `CrossPlatformBackupFixtureTests` with the SAME assertions. Any change
 * to docs/backup-format.md must keep both suites green.
 *
 *  - android-v1.til: written the way this codebase writes (union of every
 *    shared field, platform flags included)
 *  - ios-v1.til: written the way iOS writes (sorted keys, redundant tunnel
 *    `direction`, `group`/`knockPorts` always present, and — the case that
 *    used to abort the whole restore — a fractional `createdAt`)
 *
 * Passphrase: conch-parity-2026. Regenerate with tools/backup-fixtures.
 */
class CrossPlatformBackupFixtureTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/backup/$name")!!.readBytes()

    private fun decode(name: String) = BackupCodec.decrypt(fixture(name), PASSPHRASE.toCharArray())

    @Test
    fun `android fixture decodes to the shared shape`() = assertSharedShape(decode("android-v1.til"))

    @Test
    fun `ios fixture decodes to the shared shape`() = assertSharedShape(decode("ios-v1.til"))

    @Test
    fun `both fixtures decode to the same hosts keys snippets and secrets`() {
        // One canonical meaning whichever app wrote it. The iOS fixture
        // predates the pass-through of Android's platform flags, so those
        // two are normalized out here (assertSharedShape pins them on the
        // Android fixture; the iOS suite pins the pass-through).
        val a = decode("android-v1.til")
        val b = decode("ios-v1.til")
        fun hosts(p: BackupCodec.BackupPayload) =
            p.hosts.map { it.toHost().copy(forwardAgent = false, safExpose = false) }
        assertEquals(hosts(a), hosts(b))
        assertEquals(a.keys, b.keys)
        assertEquals(a.snippets, b.snippets)
        assertEquals(a.keySecrets, b.keySecrets)
        assertEquals(a.knownHosts, b.knownHosts)
        // "" and absent both mean "no password"
        assertEquals(a.hostSecrets.filterValues { it.isNotEmpty() }, b.hostSecrets.filterValues { it.isNotEmpty() })
    }

    @Test
    fun `plaintext sidecars match the encrypted fixtures`() {
        // The .json next to each .til is the documentation copy; keep them in sync.
        for (name in listOf("android-v1", "ios-v1")) {
            val sidecar = String(fixture("$name.json"), Charsets.UTF_8).trim()
            val expected = BackupCodec.decrypt(fixture("$name.til"), PASSPHRASE.toCharArray())
            assertEquals(
                BackupCodec.fingerprint(expected),
                BackupCodec.fingerprint(ConchJson.decodeFromString(BackupCodec.BackupPayload.serializer(), sidecar)),
            )
        }
    }

    private fun assertSharedShape(p: BackupCodec.BackupPayload) {
        assertEquals(1, p.version)

        // ---- hosts
        assertEquals(listOf("h-prod", "h-bastion"), p.hosts.map { it.id })
        val prod = p.hosts[0].toHost()
        assertEquals("生產機 prod", prod.alias)
        assertEquals("prod.example.com", prod.hostname)
        assertEquals(2222, prod.port)
        assertEquals("alice", prod.username)
        assertEquals(Host.AUTH_KEY, prod.authType)
        assertEquals("k-phone", prod.keyId)
        assertEquals(14.5f, prod.fontSizeSp)
        assertFalse(prod.keepAlive)
        assertTrue(prod.tmuxAutoAttach)
        assertEquals(1080, prod.socksPort)
        assertEquals("h-bastion", prod.jumpHostId)
        assertEquals("Production", prod.group)
        assertEquals(listOf(7000, 8000, 9000), prod.knockPorts)
        assertEquals(
            listOf(
                Tunnel(8080, "db.internal", 5432),
                Tunnel(9000, "127.0.0.1", 9001, remote = true, bindHost = "0.0.0.0"),
            ),
            prod.tunnels,
        )

        val bastion = p.hosts[1].toHost()
        assertEquals("", bastion.alias)
        assertEquals(Host.AUTH_PASSWORD, bastion.authType)
        assertNull(bastion.keyId)
        assertEquals(0f, bastion.fontSizeSp)
        assertTrue(bastion.keepAlive)
        assertFalse(bastion.tmuxAutoAttach)
        assertNull(bastion.jumpHostId)
        assertEquals("", bastion.group)
        assertTrue(bastion.knockPorts.isEmpty())
        assertTrue(bastion.tunnels.isEmpty())
        assertFalse(bastion.forwardAgent)
        assertFalse(bastion.safExpose)

        // ---- secrets: "" and absent both mean "no password"
        assertEquals("s3cret-パスワード🔑", p.hostSecrets["h-bastion"])
        assertTrue(p.hostSecrets["h-prod"].isNullOrEmpty())

        // ---- keys: createdAt is epoch millis, integer after decode
        assertEquals(1, p.keys.size)
        val key = p.keys[0]
        assertEquals("k-phone", key.id)
        assertEquals("my-phone", key.name)
        assertEquals("ssh-ed25519", key.algorithm)
        assertEquals(1735689600123L, key.createdAt)
        assertTrue(key.publicLine.startsWith("ssh-ed25519 AAAA"))
        assertEquals("SHA256:parityfixture", key.fingerprint)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----\n",
            p.keySecrets["k-phone"],
        )

        // ---- snippets
        assertEquals(
            listOf(Snippet("s-disk", "磁碟", "df -h"), Snippet("s-multi", "q\"uote", "echo 'multi\nline'")),
            p.snippets.map { it.toSnippet() },
        )

        // ---- known_hosts: OpenSSH lines, newline-terminated
        val lines = p.knownHosts.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[prod.example.com]:2222 ssh-ed25519 "))
        assertTrue(lines[1].startsWith("bastion.example.com ssh-ed25519 "))
    }

    @Test
    fun `platform flags survive an ios round trip`() {
        // The iOS fixture was written from an Android backup that carried
        // forwardAgent/safExpose — after the shared-format change iOS keeps
        // them; before it they were dropped. This pins that the ANDROID
        // fixture carries them (the iOS suite pins the pass-through).
        val prod = decode("android-v1.til").hosts[0].toHost()
        assertTrue(prod.forwardAgent)
        assertTrue(prod.safExpose)
    }

    companion object {
        const val PASSPHRASE = "conch-parity-2026"
    }
}
