package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PublicKey

/**
 * KnownHostsStore entry codec + TOFU status logic, no server involved:
 * OpenSSH known_hosts format, port handling, dedup, parse rejection.
 */
class KnownHostsStoreUnitTest {

    private fun tempStore(): KnownHostsStore =
        KnownHostsStore(Files.createTempDirectory("conch-kh").toFile())

    private fun ed25519Key(): PublicKey = newTestKey().use { it.publicKey }

    private fun rsaKey(): PublicKey {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair().public
    }

    @Test
    fun `hostField keeps port 22 bare and brackets other ports`() {
        assertEquals("example.com", KnownHostsStore.hostField("example.com", 22))
        assertEquals("[example.com]:2222", KnownHostsStore.hostField("example.com", 2222))
        assertEquals("[2001:db8::1]", KnownHostsStore.hostField("2001:db8::1", 22))
        assertEquals("[2001:db8::1]:2222", KnownHostsStore.hostField("2001:db8::1", 2222))
    }

    @Test
    fun `pre-bracketed hostnames are normalized not double-bracketed`() {
        assertEquals("[2001:db8::1]:2222", KnownHostsStore.hostField("[2001:db8::1]", 2222))
        assertEquals("example.com", KnownHostsStore.hostField("example.com", 22))
        assertEquals("[example.com]:2222", KnownHostsStore.hostField("[example.com]", 2222))
    }

    @Test
    fun `legacy unbracketed ipv6 entries still match`() {
        // entries written by app <= 0.9.0 stored port-22 IPv6 hosts verbatim
        val store = tempStore()
        val key = ed25519Key()
        store.file.writeText("2001:db8::1 ssh-ed25519 ${KnownHostsStore.entryFor("x", 22, key).split(" ")[2]}\n")
        assertEquals(KnownStatus.KNOWN, store.status("2001:db8::1", 22, key))
        assertEquals(KnownStatus.MISMATCH, store.status("2001:db8::1", 22, rsaKey()))
        assertEquals(listOf("ssh-ed25519"), store.algorithmsFor("2001:db8::1", 22))
    }

    @Test
    fun `legacy unbracketed ipv6 on non-22 port does not match`() {
        val store = tempStore()
        val key = ed25519Key()
        // unbracketed IPv6 with a port suffix was never a valid form; must not match
        store.file.writeText("2001:db8::1:2222 ssh-ed25519 ${KnownHostsStore.entryFor("x", 22, key).split(" ")[2]}\n")
        assertEquals(KnownStatus.UNKNOWN, store.status("2001:db8::1", 2222, key))
    }

    @Test
    fun `empty store reports unknown for everything`() {
        val store = tempStore()
        assertEquals(KnownStatus.UNKNOWN, store.status("h", 22, ed25519Key()))
    }

    @Test
    fun `added key becomes known`() {
        val store = tempStore()
        val key = ed25519Key()
        store.add("h", 22, key)
        assertEquals(KnownStatus.KNOWN, store.status("h", 22, key))
        assertEquals(KnownStatus.UNKNOWN, store.status("other", 22, key))
    }

    @Test
    fun `different key for same host is a mismatch`() {
        val store = tempStore()
        store.add("h", 2222, ed25519Key())
        assertEquals(KnownStatus.MISMATCH, store.status("h", 2222, rsaKey()))
        // different port is a different identity
        assertEquals(KnownStatus.UNKNOWN, store.status("h", 3333, rsaKey()))
    }

    @Test
    fun `same key type is irrelevant — blob decides`() {
        val store = tempStore()
        store.add("h", 22, ed25519Key())
        // a different ed25519 key must still be a mismatch, not "known"
        assertEquals(KnownStatus.MISMATCH, store.status("h", 22, ed25519Key()))
    }

    @Test
    fun `add deduplicates identical entries`() {
        val store = tempStore()
        val key = ed25519Key()
        store.add("h", 22, key)
        store.add("h", 22, key)
        store.add("h", 22, key)
        val file = Files.createTempDirectory("conch-kh2").toFile()
        val store2 = KnownHostsStore(file)
        store2.add("h", 22, key)
        val lines1 = readStoredLines(store)
        val lines2 = readStoredLines(store2)
        assertEquals(1, lines1.size)
        assertEquals(lines2, lines1)
    }

    private fun readStoredLines(store: KnownHostsStore): List<String> =
        if (store.file.exists()) store.file.readLines().filter { it.isNotBlank() } else emptyList()

    @Test
    fun `entryFor produces parseable known_hosts line`() {
        val key = ed25519Key()
        val line = KnownHostsStore.entryFor("srv", 2222, key)
        val parts = line.trim().split(Regex("\\s+"))
        assertEquals(3, parts.size)
        assertEquals("[srv]:2222", parts[0])
        assertEquals("ssh-ed25519", parts[1])
        val entry = KnownHostsStore.parseEntry(line)
        assertNotNull(entry)
        assertArrayEquals(KnownHostsStore.blobOf(key), entry!!.blob)
    }

    @Test
    fun `parseEntry rejects malformed lines`() {
        assertNull(KnownHostsStore.parseEntry(""))
        assertNull(KnownHostsStore.parseEntry("only-host"))
        assertNull(KnownHostsStore.parseEntry("host ssh-ed25519"))
        // trailing tokens after the blob are comments (legal known_hosts form),
        // but a blob that is not base64 must be rejected
        assertNull(KnownHostsStore.parseEntry("host ssh-ed25519 !!!not-base64!!! tail"))
        assertNull(KnownHostsStore.parseEntry("# comment !!! still not base64 !!!"))
    }

    @Test
    fun `marker lines never match a hostname`() {
        // lines like "@cert-authority h ssh-ed25519 <blob>" are tolerated by
        // the parser (marker becomes the host field) but can never match:
        // hostField() never produces a leading '@'
        val dir = Files.createTempDirectory("conch-kh3").toFile()
        val store = KnownHostsStore(dir)
        store.file.writeText("@cert-authority h ssh-ed25519 AAAAB3NzaC1yc2E=\n")
        assertEquals(KnownStatus.UNKNOWN, store.status("h", 22, ed25519Key()))
    }

    @Test
    fun `rsa keys produce ssh-rsa entries`() {
        val key = rsaKey()
        val line = KnownHostsStore.entryFor("h", 22, key)
        assertTrue(line.startsWith("h ssh-rsa "))
        assertEquals("ssh-rsa", KnownHostsStore.typeOf(key))
    }

    @Test
    fun `fingerprint format matches openssh sha256`() {
        val key = ed25519Key()
        val fp = KnownHostsStore.fingerprintOf(key)
        assertTrue("got: $fp", fp.startsWith("SHA256:"))
        assertFalse("base64 must be unpadded", fp.endsWith("="))
        assertEquals(
            KeyManager.fingerprintOf(KeyManager.publicLineFor(key, "x")),
            fp,
        )
    }

    @Test
    fun `algorithmsFor is per host and distinct`() {
        val store = tempStore()
        store.add("a", 22, ed25519Key())
        store.add("a", 22, ed25519Key())
        store.add("a", 22, rsaKey())
        store.add("b", 22, ed25519Key())
        assertEquals(listOf("ssh-ed25519", "ssh-rsa"), store.algorithmsFor("a", 22).sorted())
        assertEquals(listOf("ssh-ed25519"), store.algorithmsFor("b", 22))
        assertTrue(store.algorithmsFor("c", 22).isEmpty())
    }
}
