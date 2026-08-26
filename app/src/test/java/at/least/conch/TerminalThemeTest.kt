package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalThemeTest {

    @Test
    fun `every preset has exactly 16 base colors`() {
        for (theme in TerminalTheme.ALL) {
            assertEquals("theme ${theme.name}", 16, theme.base16.size)
        }
        assertEquals(5, TerminalTheme.ALL.size)
        assertEquals(
            listOf("Default", "Dracula", "Solarized Dark", "Nord", "Gruvbox Dark"),
            TerminalTheme.ALL.map { it.name }
        )
    }

    @Test
    fun `bg and fg have luminance contrast of at least 0_25`() {
        for (theme in TerminalTheme.ALL) {
            val diff = TerminalTheme.luminance(theme.defaultFg) - TerminalTheme.luminance(theme.bg)
            assertTrue(
                "theme ${theme.name}: fg/bg luminance difference $diff < 0.25",
                diff >= 0.25
            )
        }
    }

    @Test
    fun `luminance math matches WCAG anchors`() {
        assertEquals(0.0, TerminalTheme.luminance(0x000000), 1e-9)
        assertEquals(1.0, TerminalTheme.luminance(0xFFFFFF), 1e-9)
        // hand-computed: 0x839496 -> 0.2823
        assertEquals(0.2823, TerminalTheme.luminance(0x839496), 0.001)
    }

    @Test
    fun `applying a theme never aliases the shared palette`() {
        val shared = TerminalView.PALETTE
        val before = shared.copyOf()
        val dracula = TerminalTheme.DRACULA
        assertFalse("theme base16 must not BE the shared array", dracula.base16 === shared)

        val instancePalette = shared.copyOf()
        dracula.base16Into(instancePalette)
        assertNotEquals(before[0], instancePalette[0]) // Dracula black differs from default
        assertTrue("shared PALETTE must stay untouched", before.contentEquals(shared))
    }

    @Test
    fun `byName falls back to default for unknown or null names`() {
        assertEquals(TerminalTheme.DEFAULT, TerminalTheme.byName("Nonexistent"))
        assertEquals(TerminalTheme.DEFAULT, TerminalTheme.byName(null))
        assertEquals(TerminalTheme.NORD, TerminalTheme.byName("Nord"))
    }

    @Test
    fun `theme equality and data semantics`() {
        val a = TerminalTheme.DEFAULT
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
