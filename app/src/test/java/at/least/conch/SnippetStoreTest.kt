package at.least.conch

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SnippetStore — persistence round-trip, corrupt-file degradation, on-disk
 * field names (iOS SnippetStoreTests parity; the field set is the shared
 * backup wire shape). Store takes a File seam so this runs on the JVM.
 */
class SnippetStoreTest {

    private fun newStore(): Pair<File, SnippetStore> {
        val dir = Files.createTempDirectory("conch-snippets")
        val file = File(dir.toFile(), "snippets.json")
        return file to SnippetStore(file)
    }

    @Test
    fun `save then load round-trips across store instances`() {
        val (file, store) = newStore()
        store.save(
            listOf(
                Snippet(id = "s1", label = "disk", command = "df -h"),
                Snippet(id = "s2", label = "reload", command = "sudo systemctl restart nginx"),
            )
        )
        val reloaded = SnippetStore(file).load()
        assertEquals(2, reloaded.size)
        assertEquals("disk", reloaded[0].label)
        assertEquals("df -h", reloaded[0].command)
        assertEquals("sudo systemctl restart nginx", reloaded[1].command)
    }

    @Test
    fun `empty directory loads an empty list`() {
        val (_, store) = newStore()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `corrupt file degrades to empty without crashing`() {
        val (file, store) = newStore()
        file.writeText("not json at all {{{")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `persisted field names are exactly id label command`() {
        val (file, store) = newStore()
        store.save(listOf(Snippet(id = "s1", label = "l", command = "c")))
        val arr = JSONArray(file.readText())
        assertEquals(1, arr.length())
        val keys = arr.getJSONObject(0).keys().asSequence().toSet()
        assertEquals(setOf("id", "label", "command"), keys)
    }

    @Test
    fun `delete by id then re-save yields the remaining snippets`() {
        val (file, store) = newStore()
        val snippets = mutableListOf(
            Snippet(id = "s1", label = "keep", command = "a"),
            Snippet(id = "s2", label = "drop", command = "b"),
        )
        snippets.removeAll { it.id == "s2" }   // SnippetsActivity delete pattern
        store.save(snippets)
        val reloaded = SnippetStore(file).load()
        assertEquals(listOf("s1"), reloaded.map { it.id })
    }
}
