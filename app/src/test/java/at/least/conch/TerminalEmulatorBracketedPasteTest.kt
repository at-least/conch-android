package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorBracketedPasteTest {

    private fun newEmu(cols: Int = 10, rows: Int = 5): TerminalEmulator = TerminalEmulator(cols, rows)

    @Test
    fun `DECSET 2004 sets bracketed paste mode`() {
        val emu = newEmu()
        assertFalse(emu.bracketedPasteMode)
        emu.feed("\u001b[?2004h")
        assertTrue(emu.bracketedPasteMode)
    }

    @Test
    fun `DECRST 2004 clears bracketed paste mode`() {
        val emu = newEmu()
        emu.feed("\u001b[?2004h")
        assertTrue(emu.bracketedPasteMode)
        emu.feed("\u001b[?2004l")
        assertFalse(emu.bracketedPasteMode)
    }

    @Test
    fun `full reset ESC c clears bracketed paste mode`() {
        val emu = newEmu()
        emu.feed("\u001b[?2004h")
        emu.feed("\u001bc")
        assertFalse(emu.bracketedPasteMode)
    }

    @Test
    fun `other private modes do not disturb bracketed paste`() {
        val emu = newEmu()
        emu.feed("\u001b[?2004h")
        // cursor visibility + alt screen toggles in the same stream
        emu.feed("\u001b[?25l")
        emu.feed("\u001b[?1049h")
        emu.feed("\u001b[?1049l")
        emu.feed("\u001b[?25h")
        assertTrue(emu.bracketedPasteMode)
        emu.feed("\u001b[?25l")
        assertFalse(emu.cursorVisible)
        assertTrue(emu.bracketedPasteMode)
    }

    @Test
    fun `bracketed paste markers in the input stream are plain text`() {
        val emu = newEmu(cols = 20, rows = 5)
        emu.feed("\u001b[?2004h")
        emu.feed("\u001b[200~echo hi\u001b[201~")
        // markers themselves must not be rendered; the pasted payload is
        assertEquals("echo hi", emu.getRowText(0).trimEnd())
    }
}
