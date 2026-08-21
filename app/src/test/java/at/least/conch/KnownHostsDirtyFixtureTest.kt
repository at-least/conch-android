package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Real-world dirty known_hosts content, in the spirit of paramiko/asyncssh
 * fixtures: comments, blank lines, truncated base64, tabs, trailing
 * comments, hashed and wildcard entries. The store must never crash and
 * must only ever trust entries it wrote itself.
 */
class KnownHostsDirtyFixtureTest {

    private val key: java.security.PublicKey = newTestKey().use { it.publicKey }
    private val keyBlobB64: String =
        KnownHostsStore.entryFor("x", 22, key).split(" ")[2]

    /** A known_hosts file full of formats seen on real systems. */
    private val dirtyFile = """
        # comment line
           # comment with leading whitespace

        secure.example.com ssh-ed25519 $keyBlobB64
        tabbed.example.com	ssh-ed25519	$keyBlobB64
        trailing.example.com ssh-ed25519 $keyBlobB64 user@host 2024-01-01
        broken.example.com ssh-rsa AAAA
        hashed.example.com ssh-ed25519 |1|BMsIC6cUIP2zBuXR3t2LRcJYjzM=|hpkJMysjTk/+zzUUzxQEa2ieq6c=
        *.wild.example.com ssh-ed25519 $keyBlobB64
        @cert-authority ca.example.com ssh-ed25519 $keyBlobB64
        @revoked revoked.example.com ssh-ed25519 $keyBlobB64
        just-a-hostname.example.com
        two-tokens.example.com ssh-ed25519
        garbage.example.com ssh-ed25519 !!!not-base64!!!
        [bracketed.example.com]:2222 ssh-ed25519 $keyBlobB64
    """.trimIndent()

    private fun store(): KnownHostsStore {
        val dir = Files.createTempDirectory("conch-dirty").toFile()
        return KnownHostsStore(dir).apply { file.writeText(dirtyFile + "\n") }
    }

    @Test
    fun `parsing never crashes and counts only well-formed entries`() {
        val s = store()
        // parses (5): secure, tabbed, trailing, wildcard, bracketed — the only
        // lines with a structurally valid ssh key blob in slot 3.
        // rejected: blank, both comment lines ("line"/"with" decode to 3 bytes
        // — not a key blob), broken ("AAAA"), hashed ("|1|..." not base64),
        // both @marker lines (4-token form puts "ssh-ed25519" in slot 3),
        // 1-token, 2-token, bad-base64 lines.
        val parsed = dirtyFile.lines().count { KnownHostsStore.parseEntry(it) != null }
        assertEquals(5, parsed)
        assertNull(KnownHostsStore.parseEntry("just-a-hostname.example.com"))
        assertNull(KnownHostsStore.parseEntry("two-tokens.example.com ssh-ed25519"))
        assertNull(KnownHostsStore.parseEntry("garbage.example.com ssh-ed25519 !!!not-base64!!!"))
    }

    @Test
    fun `plain and tab-separated entries match their host`() {
        val s = store()
        assertEquals(KnownStatus.KNOWN, s.status("secure.example.com", 22, key))
        assertEquals(KnownStatus.KNOWN, s.status("tabbed.example.com", 22, key))
    }

    @Test
    fun `trailing comment tokens after the blob are tolerated`() {
        val s = store()
        assertEquals(KnownStatus.KNOWN, s.status("trailing.example.com", 22, key))
    }

    @Test
    fun `bracketed non-22 port entry matches that port only`() {
        val s = store()
        assertEquals(KnownStatus.KNOWN, s.status("bracketed.example.com", 2222, key))
        assertEquals(KnownStatus.UNKNOWN, s.status("bracketed.example.com", 22, key))
    }

    @Test
    fun `truncated base64 blob line is ignored like openssh does`() {
        val s = store()
        // "AAAA" decodes to 3 bytes that are not a key blob — OpenSSH skips
        // unparseable keys, so the line must not read as a changed key
        assertEquals(KnownStatus.UNKNOWN, s.status("broken.example.com", 22, key))
    }

    @Test
    fun `valid base64 garbage blob is ignored too`() {
        val s = store()
        s.file.appendText("garbage-blob.example.com ssh-ed25519 QUJDREVG\n") // decodes, no key structure
        assertEquals(KnownStatus.UNKNOWN, s.status("garbage-blob.example.com", 22, key))
        assertTrue(KnownHostsStore.parseEntry("garbage-blob.example.com ssh-ed25519 QUJDREVG") == null)
    }

    @Test
    fun `hashed entries never match plain hostnames`() {
        val s = store()
        // our store writes plain entries only; OpenSSH |1| hashed entries from
        // an imported file are ignored → user is prompted again (safe default)
        assertEquals(KnownStatus.UNKNOWN, s.status("hashed.example.com", 22, key))
    }

    @Test
    fun `wildcard entries matched literally only`() {
        val s = store()
        // "*.wild.example.com" contains no '*' magic for us: the literal name
        // matches; a subdomain does not (and safely falls back to a prompt)
        assertEquals(KnownStatus.KNOWN, s.status("*.wild.example.com", 22, key))
        assertEquals(KnownStatus.UNKNOWN, s.status("sub.wild.example.com", 22, key))
    }

    @Test
    fun `marker lines never authenticate a host`() {
        val s = store()
        assertEquals(KnownStatus.UNKNOWN, s.status("ca.example.com", 22, key))
        assertEquals(KnownStatus.UNKNOWN, s.status("revoked.example.com", 22, key))
    }

    @Test
    fun `algorithmsFor only reports real host entries`() {
        val s = store()
        assertEquals(listOf("ssh-ed25519"), s.algorithmsFor("secure.example.com", 22))
        assertEquals(listOf("ssh-ed25519"), s.algorithmsFor("tabbed.example.com", 22))
        assertEquals(emptyList<String>(), s.algorithmsFor("hashed.example.com", 22))
    }

    @Test
    fun `add does not disturb surrounding dirty lines`() {
        val s = store()
        s.add("new.example.com", 22, key)
        val lines = s.file.readLines()
        val expectedNonBlank = dirtyFile.lines().count { it.isNotBlank() } + 1
        assertEquals(expectedNonBlank, lines.count { it.isNotBlank() })
        assertNotNull(KnownHostsStore.parseEntry(lines.last()))
        // and the new entry is trusted
        assertEquals(KnownStatus.KNOWN, s.status("new.example.com", 22, key))
        // while old ones keep working
        assertEquals(KnownStatus.KNOWN, s.status("secure.example.com", 22, key))
    }
}
