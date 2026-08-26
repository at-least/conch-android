package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TOFU host-key verification over a real handshake: unknown/changed keys go
 * through the prompt, accepted keys land in known_hosts, known keys connect
 * without any prompt (background sessions).
 */
class TofuHostKeyInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-tofu").toFile()
        server = TestSshd().start()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun host() = Host(hostname = "127.0.0.1", username = server.user, authType = Host.AUTH_PASSWORD)

    /** Prompt that records requests and answers [accept]. */
    private class RecordingPrompt(val accept: Boolean) : (KeyPromptRequest, (Boolean) -> Unit) -> Unit {
        val requests = mutableListOf<KeyPromptRequest>()
        override fun invoke(p1: KeyPromptRequest, p2: (Boolean) -> Unit) {
            requests.add(p1)
            p2(accept)
        }
    }

    private fun connectWithPrompt(store: KnownHostsStore, prompt: KeyPrompt, port: Int = server.port) =
        SshConnectionFactory.connect(
            host = host().apply { this.port = port },
            prompt = prompt,
            store = store,
            keyProvider = { _, _ -> throw IllegalStateException("no key") },
            password = { server.password ?: "" },
        )

    @Test(timeout = 30_000)
    fun `unknown key accepted via prompt stores known_hosts entry`() {
        val store = KnownHostsStore(dir)
        val prompt = RecordingPrompt(accept = true)
        val ssh = connectWithPrompt(store, prompt)
        try {
            assertTrue(ssh.isAuthenticated)
        } finally {
            ssh.disconnect()
        }

        assertEquals(1, prompt.requests.size)
        val req = prompt.requests.first()
        assertEquals("ssh-ed25519", req.keyType)
        assertEquals("127.0.0.1:${server.port}", req.endpoint)
        assertFalse(req.isChange)
        assertEquals(req.fingerprint, KnownHostsStore.fingerprintOf(server.hostPublicKey))

        val file = File(dir, "known_hosts")
        assertTrue("known_hosts should exist", file.exists())
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        val entry = KnownHostsStore.parseEntry(lines[0])
        assertNotNull(entry)
        assertEquals("[127.0.0.1]:${server.port}", entry!!.host)
        assertEquals("ssh-ed25519", entry.algorithm)
        assertTrue(
            "stored blob must match the server host key",
            entry.blob.contentEquals(KnownHostsStore.blobOf(server.hostPublicKey)),
        )
    }

    @Test(timeout = 30_000)
    fun `known key connects with no prompt`() {
        val store = KnownHostsStore(dir)
        val prompt = RecordingPrompt(accept = true)
        connectWithPrompt(store, prompt).disconnect()

        val second = RecordingPrompt(accept = false) // would reject if asked again
        val ssh = connectWithPrompt(store, second)
        try {
            assertTrue(ssh.isAuthenticated)
        } finally {
            ssh.disconnect()
        }
        assertEquals("no prompt expected on second connect", 0, second.requests.size)
    }

    @Test(timeout = 30_000)
    fun `unknown key rejected via prompt fails handshake and stores nothing`() {
        val store = KnownHostsStore(dir)
        val prompt = RecordingPrompt(accept = false)
        try {
            connectWithPrompt(store, prompt)
            fail("expected transport failure after rejecting the host key")
        } catch (_: Exception) {
        }
        assertEquals(1, prompt.requests.size)
        assertFalse("nothing should be stored when rejected", File(dir, "known_hosts").exists())
    }

    @Test(timeout = 30_000)
    fun `unknown key without prompt fails for background sessions`() {
        val store = KnownHostsStore(dir)
        try {
            SshConnectionFactory.connect(
                host = host().apply { port = server.port },
                prompt = null,
                store = store,
                keyProvider = { _, _ -> throw IllegalStateException("no key") },
                password = { server.password ?: "" },
            )
            fail("expected transport failure")
        } catch (_: Exception) {
        }
        assertFalse(File(dir, "known_hosts").exists())
    }

    @Test(timeout = 30_000)
    fun `changed key warns via prompt and accepted key is stored`() {
        val store = KnownHostsStore(dir)
        connectWithPrompt(store, RecordingPrompt(accept = true)).disconnect()

        // rotate the server key on the same port
        val port = server.port
        server.close()
        server = TestSshd(hostKeyPair = TestSshd.hostKeyRsa(), fixedPort = port).start()

        val prompt = RecordingPrompt(accept = true)
        val ssh = connectWithPrompt(store, prompt)
        try {
            assertTrue(ssh.isAuthenticated)
        } finally {
            ssh.disconnect()
        }

        assertEquals(1, prompt.requests.size)
        assertTrue("changed key must set isChange", prompt.requests.first().isChange)
        assertEquals("ssh-rsa", prompt.requests.first().keyType)

        val lines = File(dir, "known_hosts").readLines().filter { it.isNotBlank() }
        assertEquals("old and new key should both be stored", 2, lines.size)
        assertEquals(
            listOf("ssh-ed25519", "ssh-rsa"),
            lines.mapNotNull { KnownHostsStore.parseEntry(it) }.map { it.algorithm }.sorted(),
        )
    }

    @Test(timeout = 30_000)
    fun `changed key rejected via prompt fails`() {
        val store = KnownHostsStore(dir)
        connectWithPrompt(store, RecordingPrompt(accept = true)).disconnect()

        val port = server.port
        server.close()
        server = TestSshd(hostKeyPair = TestSshd.hostKeyRsa(), fixedPort = port).start()

        try {
            connectWithPrompt(store, RecordingPrompt(accept = false))
            fail("expected transport failure after rejecting changed key")
        } catch (_: Exception) {
        }
        assertEquals(
            "only the original entry should remain",
            1,
            File(dir, "known_hosts").readLines().count { it.isNotBlank() }
        )
    }

    @Test(timeout = 30_000)
    fun `crashing prompt rejects the key instead of hanging`() {
        val store = KnownHostsStore(dir)
        val crashing: KeyPrompt = { _, _ -> throw RuntimeException("UI blew up") }
        try {
            connectWithPrompt(store, crashing)
            fail("expected transport failure")
        } catch (_: Exception) {
        }
        assertFalse(File(dir, "known_hosts").exists())
    }

    @Test(timeout = 30_000)
    fun `algorithmsFor returns stored host key algorithms`() {
        val store = KnownHostsStore(dir)
        assertEquals(emptyList<String>(), store.algorithmsFor("127.0.0.1", server.port))

        store.add("127.0.0.1", server.port, server.hostPublicKey)
        store.add("127.0.0.1", server.port, server.hostPublicKey)
        assertEquals(listOf("ssh-ed25519"), store.algorithmsFor("127.0.0.1", server.port))

        val otherPort = server.port + 1
        assertTrue("different port must be a different identity", store.algorithmsFor("127.0.0.1", otherPort).isEmpty())
    }
}
