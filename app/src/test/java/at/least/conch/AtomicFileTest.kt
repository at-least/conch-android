package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AtomicFile — crash-safe store replacement, plus the store behaviors built
 * on it: a corrupt hosts.json is preserved as *.corrupt instead of being
 * silently overwritten by the next save.
 */
class AtomicFileTest {

    private fun tmpDir(): File = Files.createTempDirectory("conch-atomic").toFile()

    @Test
    fun `write creates the file and leaves no temp behind`() {
        val dir = tmpDir()
        val f = File(dir, "store.json")
        AtomicFile.write(f, "{\"a\":1}")
        assertEquals("{\"a\":1}", f.readText())
        assertFalse(File(dir, "store.json.tmp").exists())
    }

    @Test
    fun `rewrite replaces content atomically`() {
        val dir = tmpDir()
        val f = File(dir, "store.json")
        AtomicFile.write(f, "one")
        AtomicFile.write(f, "two")
        assertEquals("two", f.readText())
        assertFalse(File(dir, "store.json.tmp").exists())
    }

    @Test
    fun `corrupt hosts file is preserved as corrupt copy and loads empty`() {
        val dir = tmpDir()
        val context = io.mockk.mockk<android.content.Context> {
            io.mockk.every { filesDir } returns dir
        }
        val file = File(dir, "hosts.json")
        file.writeText("{ this is not json ]")

        val hosts = HostStore(context).load()
        assertTrue(hosts.isEmpty())
        val copy = File(dir, "hosts.json.corrupt")
        assertTrue("corrupt file must be preserved for recovery", copy.exists())
        assertEquals("{ this is not json ]", copy.readText())
    }

    @Test
    fun `keystore failure during legacy migration keeps every entry and does not rewrite the file`() {
        val dir = tmpDir()
        val context = io.mockk.mockk<android.content.Context> {
            io.mockk.every { filesDir } returns dir
        }
        val file = File(dir, "hosts.json")
        file.writeText(
            """[
                {"id":"h1","hostname":"one","username":"u","password":"p1"},
                {"id":"h2","hostname":"two","username":"u","password":"p2"},
                {"id":"h3","hostname":"three","username":"u"}
            ]"""
        )
        val before = file.readText()
        io.mockk.mockkObject(SecretsStore) {
            io.mockk.every { SecretsStore.get(any()) } returns null
            io.mockk.every { SecretsStore.put(any(), any()) } answers {
                if (firstArg<String>() == "host-pw:h2") error("keystore hiccup")
            }
            val hosts = HostStore(context).load()
            // h2's migration failed; h1 migrated, h3 needs nothing. Every
            // host stays listed (dropping h2 and re-saving would have
            // persisted the loss of its password), and the file is left as
            // it was so the migration simply runs again next launch.
            assertEquals(listOf("h1", "h2", "h3"), hosts.map { it.id })
        }
        assertEquals("file must not be rewritten while a password is still unmigrated", before, file.readText())
    }
}
