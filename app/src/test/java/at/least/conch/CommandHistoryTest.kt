package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CommandHistoryTest {

    private fun newStore(): Pair<CommandHistoryStore, File> {
        val dir = Files.createTempDirectory("conch-history")
        val file = File(dir.toFile(), "command_history.bin")
        val store = CommandHistoryStore(
            readFile = { if (file.exists() && file.length() > 0) file.readBytes() else null },
            writeFile = { file.writeBytes(it) },
            key = HistoryCrypto.newKey(),
        )
        return store to file
    }

    // ---------------------------------------------------------------- codec

    @Test
    fun `json codec roundtrip preserves entries`() {
        val entries = listOf(
            HistoryEntry("h1", "uptime", 1000L),
            HistoryEntry("h2", "echo 'multi\nline'", 2000L),
            HistoryEntry("h1", "中文コマンド", 3000L),
        )
        val decoded = CommandHistoryStore.historyFromJson(CommandHistoryStore.historyToJson(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun `json codec tolerates garbage`() {
        assertTrue(CommandHistoryStore.historyFromJson("not json").isEmpty())
        assertTrue(CommandHistoryStore.historyFromJson("").isEmpty())
    }

    // ------------------------------------------------------ append semantics

    @Test
    fun `append skips blank and control-only lines`() {
        val entries = listOf(HistoryEntry("h1", "old", 1L))
        assertEquals(1, CommandHistoryStore.append(entries, "h1", "", 2L).size)
        assertEquals(1, CommandHistoryStore.append(entries, "h1", "   \t ", 2L).size)
        assertEquals(1, CommandHistoryStore.append(entries, "h1", "\u0001\u0002", 2L).size)
        assertEquals(2, CommandHistoryStore.append(entries, "h1", "ls -la", 2L).size)
    }

    @Test
    fun `append suppresses consecutive duplicates per host`() {
        val entries = listOf(HistoryEntry("h1", "ls", 1L))
        assertEquals(1, CommandHistoryStore.append(entries, "h1", "ls", 2L).size)
        // duplicate for ANOTHER host still records
        assertEquals(2, CommandHistoryStore.append(entries, "h2", "ls", 2L).size)
        // non-consecutive duplicate records again
        val two = CommandHistoryStore.append(entries, "h1", "pwd", 2L)
        assertEquals(3, CommandHistoryStore.append(two, "h1", "ls", 3L).size)
    }

    @Test
    fun `append caps the ring at 1000 dropping oldest`() {
        var entries = (1..1000).map { HistoryEntry("h1", "cmd$it", it.toLong()) }
        entries = CommandHistoryStore.append(entries, "h1", "cmd-new", 1001L)
        assertEquals(1000, entries.size)
        assertEquals("cmd-new", entries.last().text)
        assertEquals("cmd2", entries.first().text) // cmd1 evicted
    }

    // ------------------------------------------------------ search semantics

    @Test
    fun `search is host-scoped newest-first and case-insensitive`() {
        val entries = listOf(
            HistoryEntry("h1", "docker ps", 1L),
            HistoryEntry("h2", "docker ps", 2L),
            HistoryEntry("h1", "systemctl status nginx", 3L),
            HistoryEntry("h1", "Docker logs -f web", 4L),
        )
        val hits = CommandHistoryStore.search(entries, "h1", "docker")
        assertEquals(listOf(4L, 1L), hits.map { it.ts })
        assertEquals(emptyList<Long>(), CommandHistoryStore.search(entries, "h2", "nginx").map { it.ts })
        // blank query returns all for the host, newest first
        assertEquals(listOf(4L, 3L, 1L), CommandHistoryStore.search(entries, "h1", "  ").map { it.ts })
    }

    // ------------------------------------------------------- crypto + disk

    @Test
    fun `on-disk bytes contain no plaintext command`() {
        val (store, file) = newStore()
        store.record("h1", "SECRET-COMMAND-TOKEN-xyzzy")
        assertTrue(file.exists())
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("plaintext command must not appear in the file", raw.contains("SECRET-COMMAND-TOKEN"))
        // and the ciphertext still round-trips through the SAME key
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("SECRET-COMMAND-TOKEN-xyzzy", loaded[0].text)
    }

    @Test
    fun `store record-load roundtrip across instances with same key`() {
        val dir = Files.createTempDirectory("conch-history2")
        val file = File(dir.toFile(), "command_history.bin")
        val key = HistoryCrypto.newKey()
        fun mk() = CommandHistoryStore(
            readFile = { if (file.exists() && file.length() > 0) file.readBytes() else null },
            writeFile = { file.writeBytes(it) },
            key = key,
        )
        mk().record("h1", "first")
        mk().record("h1", "second")
        mk().record("h1", "second") // dedup
        mk().record("h2", "other host")
        val loaded = mk().load()
        assertEquals(listOf("first", "second"), loaded.filter { it.hostId == "h1" }.map { it.text })
        assertEquals(listOf("other host"), loaded.filter { it.hostId == "h2" }.map { it.text })
    }

    @Test
    fun `wrong key yields empty load not crash`() {
        val (store, file) = newStore()
        store.record("h1", "secret")
        val stranger = CommandHistoryStore(
            readFile = { file.readBytes() },
            writeFile = {},
            key = HistoryCrypto.newKey(),
        )
        assertTrue(stranger.load().isEmpty())
    }

    @Test
    fun `clear empties the history`() {
        val (store, _) = newStore()
        store.record("h1", "gone")
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    // ----------------------------------------------------------- HistoryCrypto

    @Test
    fun `crypto roundtrip and tamper detection`() {
        val key = HistoryCrypto.newKey()
        assertEquals(32, key.size)
        val blob = HistoryCrypto.encrypt(key, "hello")
        assertEquals("hello", HistoryCrypto.decrypt(key, blob))
        assertNull(
            HistoryCrypto.decrypt(key, blob.clone().also { it[blob.size - 1] = (it.last().toInt() xor 1).toByte() })
        )
        assertNotNull(HistoryCrypto.decrypt(key, blob))
    }

    // ------------------------------------------------------- stored-key plan

    @Test
    fun `valid stored key plans USE and decodes`() {
        val key = HistoryCrypto.newKey()
        val stored = CommandHistoryStore.encodeKey(key)
        assertEquals(CommandHistoryStore.KeyPlan.USE, CommandHistoryStore.planForKey(stored, aliasPresent = true))
        assertTrue(CommandHistoryStore.decodeKey(stored)!!.contentEquals(key))
    }

    @Test
    fun `wrong-length stored key plans REGENERATE`() {
        val short = CommandHistoryStore.encodeKey(ByteArray(16))
        assertEquals(CommandHistoryStore.KeyPlan.REGENERATE, CommandHistoryStore.planForKey(short, true))
        assertNull(CommandHistoryStore.decodeKey(short))
    }

    @Test
    fun `undecodable stored key plans REGENERATE`() {
        assertEquals(CommandHistoryStore.KeyPlan.REGENERATE, CommandHistoryStore.planForKey("!!!", true))
        assertNull(CommandHistoryStore.decodeKey("!!!"))
    }

    @Test
    fun `unreadable-but-present key plans DISABLED not REGENERATE`() {
        // get() returned null while the alias exists: regenerating would
        // permanently brick the history file.
        assertEquals(CommandHistoryStore.KeyPlan.DISABLED, CommandHistoryStore.planForKey(null, true))
    }

    @Test
    fun `absent key plans REGENERATE first run`() {
        assertEquals(CommandHistoryStore.KeyPlan.REGENERATE, CommandHistoryStore.planForKey(null, false))
    }

    @Test
    fun `disabled store never writes and loads empty`() {
        var wrote = false
        val disabled = CommandHistoryStore(
            readFile = { null },
            writeFile = { wrote = true },
            key = null,
        )
        disabled.record("h1", "must-not-persist")
        assertFalse(wrote)
        assertTrue(disabled.load().isEmpty())
    }

    @Test
    fun `concurrent record and clear never corrupt the file`() {
        val dir = Files.createTempDirectory("conch-history-conc")
        val file = File(dir.toFile(), "command_history.bin")
        val key = HistoryCrypto.newKey()
        fun mk(): CommandHistoryStore = CommandHistoryStore(
            readFile = { if (file.exists() && file.length() > 0) file.readBytes() else null },
            writeFile = { file.writeBytes(it) },
            key = key,
        )
        val writers = (0 until 8).map { w ->
            Thread {
                val store = mk()
                repeat(40) { store.record("h$w", "cmd-$w-$it") }
            }
        }
        val clearer = Thread {
            val store = mk()
            repeat(30) {
                store.clear()
                Thread.sleep(2)
            }
        }
        (writers + clearer).forEach { it.start() }
        (writers + clearer).forEach { it.join() }
        // The real invariant: every write left a COMPLETE ciphertext behind.
        // `load()` swallows a decrypt failure and returns an empty list, so
        // asking it is not enough — go at the bytes directly. An empty file is
        // the legitimate "clear() wrote last" outcome.
        val raw = file.readBytes()
        if (raw.isNotEmpty()) {
            assertNotNull(
                "file is a torn ciphertext (${raw.size} bytes): a record/clear race corrupted it",
                HistoryCrypto.decrypt(key, raw),
            )
        }
        // ...and the store is still usable afterwards: a fresh record must
        // round-trip through the same file.
        val store = mk()
        store.record("sentinel", "after-the-race")
        val reloaded = store.load()
        assertTrue(
            "a record after the race did not survive a reload, got $reloaded",
            reloaded.any { it.hostId == "sentinel" && it.text == "after-the-race" },
        )
    }
}
