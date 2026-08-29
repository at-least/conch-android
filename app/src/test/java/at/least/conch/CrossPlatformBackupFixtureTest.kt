package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared-format pins: the SAME fixtures are checked into conch-ios
 * (`ConchTests/Fixtures/Backup/`) and decoded by its
 * `CrossPlatformBackupFixtureTests` with the SAME assertions. Any change
 * to docs/backup-format.md must keep both suites green.
 *
 *  - full-v1.conchbak: every field populated
 *  - sparse-v1.conchbak: minimal writer + unknown keys at every level
 *
 * Passphrase: conch-parity-2026. Regenerate with tools/backup-fixtures.
 */
class CrossPlatformBackupFixtureTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/backup/$name")!!.readBytes()

    private fun decode(name: String) = BackupCodec.decrypt(fixture(name), PASSPHRASE.toCharArray())

    @Test
    fun `plaintext sidecars match the encrypted fixtures`() {
        for (name in listOf("full-v1", "sparse-v1")) {
            val sidecar = String(fixture("$name.json"), Charsets.UTF_8).trim()
            assertEquals(name, BackupCodec.payloadFromJson(sidecar), decode("$name.conchbak"))
        }
    }

    @Test
    fun `full fixture re-encodes to its own canonical bytes`() {
        // What this app writes for that data IS the fixture: sorted keys,
        // no whitespace, raw unicode, optionals absent.
        val sidecar = String(fixture("full-v1.json"), Charsets.UTF_8).trim()
        assertEquals(sidecar, BackupCodec.payloadToJson(decode("full-v1.conchbak")))
    }

    @Test
    fun `full fixture decodes to the shared shape`() {
        val p = decode("full-v1.conchbak")
        assertEquals("2026-08-29T05:30:00Z", p.exportedAt)
        assertEquals(BackupOrigin("android", "0.9.1"), p.origin)

        // ---- hosts
        assertEquals(listOf("h-prod", "h-bastion", "h-v6"), p.hosts.map { it.id })
        val prod = p.hosts[0].toHost()
        assertEquals("生產機 prod", prod.alias)
        assertEquals("prod.example.com", prod.hostname)
        assertEquals(2222, prod.port)
        assertEquals("alice", prod.username)
        assertEquals(Host.AUTH_KEY, prod.authType)
        assertEquals("k-phone", prod.keyId)
        assertNull(p.hosts[0].password)
        assertEquals(14.5f, prod.fontSizeSp)
        assertFalse(prod.keepAlive)
        assertTrue(prod.tmuxAutoAttach)
        assertEquals(1080, prod.socksPort)
        assertEquals("h-bastion", prod.jumpHostId)
        assertEquals("Production", prod.group)
        assertEquals(listOf(7000, 8000, 9000), prod.knockPorts)
        assertTrue(prod.forwardAgent)
        assertTrue(prod.safExpose)
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
        assertEquals("s3cret-パスワード🔑", p.hosts[1].password)
        assertEquals(0f, bastion.fontSizeSp)
        assertTrue(bastion.keepAlive)
        assertFalse(bastion.tmuxAutoAttach)
        assertNull(bastion.jumpHostId)
        assertEquals("", bastion.group)
        assertTrue(bastion.knockPorts.isEmpty())
        assertTrue(bastion.tunnels.isEmpty())
        assertEquals(0, bastion.socksPort)
        assertFalse(bastion.forwardAgent)
        assertFalse(bastion.safExpose)

        val v6 = p.hosts[2].toHost()
        assertEquals("2001:db8::10", v6.hostname)
        assertNull(p.hosts[2].password) // password auth, nothing stored → prompt

        // ---- keys
        assertEquals(listOf("k-phone", "k-orphan"), p.keys.map { it.id })
        val key = p.keys[0].toInfo()
        assertEquals("my-phone", key.name)
        assertEquals("ssh-ed25519", key.algorithm)
        assertEquals(1735689600123L, key.createdAt)
        assertTrue(key.publicLine.startsWith("ssh-ed25519 AAAA"))
        assertEquals("SHA256:parityfixture", key.fingerprint)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----\n",
            p.keys[0].privateKey,
        )
        assertNull(p.keys[1].privateKey)
        assertEquals(1748779200000L, p.keys[1].toInfo().createdAt)
        assertEquals(listOf("k-phone"), BackupManager.keyIdsToImport(emptySet(), p.keys))

        // ---- snippets
        assertEquals(
            listOf(Snippet("s-disk", "磁碟", "df -h"), Snippet("s-multi", "q\"uote", "echo 'multi\nline' && ls /")),
            p.snippets.map { it.toSnippet() },
        )

        // ---- known hosts → OpenSSH lines
        assertEquals(4, p.knownHosts.size)
        assertTrue(p.knownHosts.all { it.isValid })
        val lines = p.knownHosts.map { it.toLine() }
        assertTrue(lines[0].startsWith("[prod.example.com]:2222 ssh-ed25519 AAAAC3"))
        assertTrue(lines[1].startsWith("bastion.example.com ssh-ed25519 AAAAC3"))
        assertTrue(lines[2].startsWith("[2001:db8::10] ssh-ed25519 AAAAC3"))
        assertTrue(lines[3].startsWith("@revoked old.example.com ssh-ed25519 AAAAC3"))
        assertEquals("revoked", p.knownHosts[3].marker)
        // and back again, losslessly
        assertEquals(p.knownHosts, lines.map { BackupKnownHost.parseLine(it) })
    }

    @Test
    fun `sparse fixture applies every default and ignores unknown keys`() {
        val p = decode("sparse-v1.conchbak")
        assertEquals(BackupTime.EPOCH, p.exportedAt)
        assertEquals(BackupOrigin(), p.origin)

        assertEquals(1, p.hosts.size)
        val h = p.hosts[0].toHost()
        assertEquals("h-min", h.id)
        assertEquals("", h.alias)
        assertEquals("min.example.com", h.hostname)
        assertEquals(22, h.port)
        assertEquals("bob", h.username)
        assertEquals("", h.group)
        assertEquals(Host.AUTH_PASSWORD, h.authType)
        assertNull(h.keyId)
        assertNull(p.hosts[0].password)
        assertNull(h.jumpHostId)
        assertTrue(h.knockPorts.isEmpty())
        assertTrue(h.tunnels.isEmpty())
        assertEquals(0, h.socksPort)
        assertEquals(0f, h.fontSizeSp)
        assertTrue(h.keepAlive)
        assertTrue(h.tmuxAutoAttach)
        assertFalse(h.forwardAgent)
        assertFalse(h.safExpose)

        assertEquals(1, p.keys.size)
        val k = p.keys[0].toInfo()
        assertEquals("k-min", k.id)
        assertEquals("", k.name)
        assertEquals("", k.algorithm)
        assertEquals(0L, k.createdAt)
        assertEquals("", k.publicLine)
        assertEquals("", k.fingerprint)
        assertTrue(p.keys[0].privateKey!!.startsWith("-----BEGIN OPENSSH"))

        assertEquals(listOf(Snippet("s-min", "", "")), p.snippets.map { it.toSnippet() })

        assertEquals(1, p.knownHosts.size)
        assertEquals(22, p.knownHosts[0].port)
        assertNull(p.knownHosts[0].marker)
        assertTrue(p.knownHosts[0].toLine().startsWith("min.example.com ssh-ed25519 "))
    }

    companion object {
        const val PASSPHRASE = "conch-parity-2026"
    }
}
