package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommandPaletteModel — pure filter/rank, Android port of iOS's model.
 * Verifies prefix > substring, snippets win ties, empty-query recent history,
 * limit cap, label-only snippet match.
 */
class CommandPaletteModelTest {

    @Test
    fun `empty query returns recent history newest first`() {
        val history = listOf("old", "mid", "recent")
        val out = CommandPaletteModel.filter("", history, emptyList())
        assertEquals(listOf("recent", "mid", "old"), out.map { it.text })
        assertTrue(out.all { it.origin == CommandPaletteModel.Origin.HISTORY })
    }

    @Test
    fun `empty query capped to limit`() {
        val history = (1..100).map { "c$it" }
        val out = CommandPaletteModel.filter("", history, emptyList(), limit = 10)
        assertEquals(10, out.size)
        // newest 10 of 1..100 → c100..c91
        assertEquals("c100", out.first().text)
        assertEquals("c91", out.last().text)
    }

    @Test
    fun `prefix match outranks substring match`() {
        val history = listOf("docker ps", "something docker")
        val out = CommandPaletteModel.filter("docker", history, emptyList())
        assertEquals("docker ps", out.first().text)
    }

    @Test
    fun `snippets win ties over history`() {
        val history = listOf("uptime")
        val snippets = listOf("up" to "uptime")
        val out = CommandPaletteModel.filter("uptime", history, snippets)
        // both prefix (score 0); snippet tie=0 beats history tie=0? iOS:
        // sorted by (score, tie) — snippet appended first with tie 0,
        // history with tie 0 too; stable sort preserves snippet-first order.
        assertEquals(CommandPaletteModel.Origin.SNIPPET, out.first().origin)
        assertEquals("uptime", out.first().text)
        assertEquals("up", out.first().label)
    }

    @Test
    fun `snippet matched by label only still appears`() {
        val snippets = listOf("disk usage" to "df -h")
        val out = CommandPaletteModel.filter("disk", emptyList(), snippets)
        assertEquals(1, out.size)
        assertEquals("df -h", out.first().text)
        assertEquals("disk usage", out.first().label)
    }

    @Test
    fun `no matches returns empty`() {
        val out = CommandPaletteModel.filter("zzz", listOf("abc"), listOf("lbl" to "cmd"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `results capped to limit after ranking`() {
        val history = (1..80).map { "run$it" }
        val out = CommandPaletteModel.filter("run", history, emptyList(), limit = 25)
        assertEquals(25, out.size)
    }
}
