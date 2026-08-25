package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorScrollbackTest {

    @Test
    fun `scrolling off the top pushes rows into scrollback`() {
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("L1\r\nL2\r\nL3\r\nL4")
        // 3-row screen: L1 scrolled into history, screen shows L2 L3 L4
        assertEquals(1, emu.scrollbackSize)
        assertEquals("L1", emu.getScrollbackRowText(0))
        assertEquals("L2", emu.getRowText(0))
        assertEquals("L4", emu.getRowText(2))
    }

    @Test
    fun `many lines cap and order preserved`() {
        val emu = TerminalEmulator(cols = 10, rows = 4)
        val feed = (1..60).joinToString("") { "line%02d\r\n".format(it) }.dropLast(2)
        emu.feed(feed)
        // last 4 lines on screen
        assertEquals("line57", emu.getRowText(0))
        assertEquals("line60", emu.getRowText(3))
        // scrollback has line01..line56 in order
        assertEquals(56, emu.scrollbackSize)
        assertEquals("line01", emu.getScrollbackRowText(0))
        assertEquals("line56", emu.getScrollbackRowText(55))
    }

    @Test
    fun `alt screen does not write scrollback`() {
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("main\r\n")
        emu.feed("\u001b[?1049h")
        repeat(20) { emu.feed("altline\r\n") }
        assertEquals(0, emu.scrollbackSize)
        emu.feed("\u001b[?1049l")
        assertEquals(0, emu.scrollbackSize)
    }

    @Test
    fun `scroll region grows transcript (upstream semantics, differs from xterm)`() {
        val emu = TerminalEmulator(cols = 10, rows = 5)
        emu.feed("\u001b[2;4r") // region rows 1..3
        repeat(10) { emu.feed("x\r\n") }
        // PINNED DIVERGENCE: the vendored Termux engine advances the
        // transcript on ANY scrollDownOneLine — including region-confined
        // scrolls (xterm keeps scrollback untouched for those). Old in-house
        // engine pinned 0 here; upstream semantics accepted with the swap.
        assertTrue(emu.scrollbackSize > 0)
    }

    @Test
    fun `scrollback styles and wide chars survive`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[31mred\u001b[0m こん\r\n")
        emu.feed("b1\r\nb2\r\nb3\r\n")
        assertTrue(emu.scrollbackSize >= 2)
        val row = emu.getScrollbackRowText(0)
        assertTrue(row.contains("red"))
        assertTrue(row.contains("こん"))
    }

    @Test
    fun `clearScrollback empties history`() {
        val emu = TerminalEmulator(cols = 5, rows = 2)
        emu.feed("a\r\nb\r\nc\r\n")
        assertTrue(emu.scrollbackSize > 0)
        emu.clearScrollback()
        assertEquals(0, emu.scrollbackSize)
    }
}
