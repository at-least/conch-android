package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackupManager merge semantics (iOS BackupCodecTests
 * testExportRestoreMergeSemantics parity): an import never destroys current
 * data — existing ids kept verbatim, only new ids append. Extracted into
 * pure companion functions so the decisions run on the JVM.
 *
 * Not covered here (Android-bound, no Robolectric in this repo): the
 * SecretsStore side of restore (host-pw:/key-priv: writes) and collect()'s
 * secret gathering — codec round-trip (BackupCodecTest) proves secrets
 * travel inside the blob; the store wiring stays manual-QA.
 */
class BackupManagerMergeTest {

    private fun host(id: String, alias: String) = Host(
        id = id,
        alias = alias,
        hostname = "$id.example.com",
        port = 22,
        username = "user",
        authType = "PASSWORD",
    )

    @Test
    fun `existing host id is kept verbatim and only new ids append`() {
        val existing = listOf(host("h1", "old-alias"))
        val incoming = listOf(
            host("h1", "EDITED-alias"), // same id — must NOT overwrite
            host("h2", "new-host"),
        )
        val (merged, added) = BackupManager.mergeHosts(existing, incoming)

        assertEquals(listOf("h2"), added)
        assertEquals(2, merged.size)
        assertEquals("old-alias", merged.first { it.id == "h1" }.alias)
        assertTrue(merged.any { it.id == "h2" })
    }

    @Test
    fun `hosts merge with nothing new is a no-op`() {
        val existing = listOf(host("h1", "a"))
        val (merged, added) = BackupManager.mergeHosts(existing, listOf(host("h1", "b")))
        assertTrue(added.isEmpty())
        assertEquals(existing, merged)
    }

    @Test
    fun `key import skips known ids and keys without their private half`() {
        val incoming = listOf(
            KeyWire("k1", "n", "ssh-ed25519", 0L, "pub", "fp"), // already known
            KeyWire("k2", "n", "ssh-ed25519", 0L, "pub", "fp"), // no pem — useless
            KeyWire("", "n", "ssh-ed25519", 0L, "pub", "fp"), // no id
            KeyWire("k3", "n", "ssh-ed25519", 0L, "pub", "fp"), // good
        )
        val secrets = mapOf("k1" to "PEM", "k3" to "PEM")
        val imported = BackupManager.keyIdsToImport(setOf("k1"), incoming, secrets)
        assertEquals(listOf("k3"), imported)
    }

    @Test
    fun `snippet merge keeps existing and appends new ids only`() {
        val existing = listOf(Snippet(id = "s1", label = "old", command = "a"))
        val incoming = listOf(
            Snippet(id = "s1", label = "EDITED", command = "z"),
            Snippet(id = "s2", label = "new", command = "b"),
        )
        val (merged, added) = BackupManager.mergeSnippets(existing, incoming)

        assertEquals(listOf("s2"), added)
        assertEquals("old", merged.first { it.id == "s1" }.label)
        assertEquals("b", merged.first { it.id == "s2" }.command)
    }

    @Test
    fun `known hosts merge is a dedup union that reports growth`() {
        val current = listOf(
            "[a.example.com]:2222 ssh-ed25519 AAAA",
            "b.example.com ssh-ed25519 BBBB",
        )
        val incoming = listOf(
            "b.example.com ssh-ed25519 BBBB", // duplicate
            "c.example.com ssh-ed25519 CCCC", // new
            "", // blank
        )
        val (union, grew) = BackupManager.mergeKnownHostsLines(current, incoming)

        assertTrue(grew)
        assertEquals(3, union.size)
        assertTrue(union.containsAll(current))

        val (same, grewAgain) = BackupManager.mergeKnownHostsLines(union, incoming)
        assertFalse(grewAgain)
        assertEquals(union, same)
    }
}
