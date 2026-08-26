package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextSelection math pins: normalization under reversed drags, cell-level
 * isSelected bounds (first/last row partial spans, middle rows full), and
 * column-accurate extraction including wide code points and scrollback
 * rows. Parity driver: ConnectBot "Can't copy beyond viewable area".
 */
class TextSelectionTest {

    private fun newEmu(cols: Int = 20, rows: Int = 5) = TerminalEmulator(cols, rows)

    @Test
    fun `normalized orders anchors regardless of drag direction`() {
        val sel = TextSelection()
        sel.startAnchor(3, 10)
        sel.moveCaret(1, 2)
        val (s, e) = sel.normalized()!!
        assertEquals(1, s.externalRow)
        assertEquals(2, s.col)
        assertEquals(3, e.externalRow)
        assertEquals(10, e.col)
    }

    @Test
    fun `isSelected spans partial first and last rows and full middle rows`() {
        val sel = TextSelection()
        sel.startAnchor(0, 2)
        sel.moveCaret(2, 4)

        assertFalse(sel.isSelected(0, 1))
        assertTrue(sel.isSelected(0, 2))
        assertTrue(sel.isSelected(0, 19))
        assertTrue(sel.isSelected(1, 0))
        assertTrue(sel.isSelected(1, 19))
        assertTrue(sel.isSelected(2, 0))
        assertTrue(sel.isSelected(2, 4))
        assertFalse(sel.isSelected(2, 5))
        assertFalse(sel.isSelected(3, 0))
    }

    @Test
    fun `same-row selection is a column range`() {
        val sel = TextSelection()
        sel.startAnchor(2, 8)
        sel.moveCaret(2, 3)
        val (s, e) = sel.normalized()!!
        assertEquals(3, s.col)
        assertEquals(8, e.col)
        assertEquals(2, s.externalRow)
        assertEquals(2, e.externalRow)
    }

    @Test
    fun `extraction is column-accurate across rows`() {
        val emu = newEmu()
        emu.feed("hello world\r\nsecond line here\r\nthird")
        val sel = TextSelection()
        // "llo wor" on row 0 (cols 2..8) + whole row 1 + "hir" on row 2
        sel.startAnchor(0, 2)
        sel.moveCaret(2, 2)
        assertEquals("llo world\nsecond line here\nthi", TextSelection.selectedText(emu, sel))
    }

    @Test
    fun `wide code points are copied whole at their start column`() {
        val emu = newEmu()
        emu.feed("漢字ABC\r\n")
        val sel = TextSelection()
        // cols: 漢=0..1, 字=2..3, A=4, B=5, C=6 — select cols 2..5 → "字AB"
        sel.startAnchor(0, 2)
        sel.moveCaret(0, 5)
        assertEquals("字AB", TextSelection.selectedText(emu, sel))
    }

    @Test
    fun `selection reaches into scrollback via negative external rows`() {
        val emu = TerminalEmulator(20, 3)
        // 6 lines through a 3-row screen: first 3 lines live in scrollback
        emu.feed("L1 alpha\r\nL2 beta\r\nL3 gamma\r\nL4 delta\r\nL5 eps\r\nL6 live")
        assertEquals(3, emu.scrollbackSize)

        val sel = TextSelection()
        // scrollback rows are -3..-1 (oldest first); pick -3..-2 → L1, L2
        sel.startAnchor(-3, 0)
        sel.moveCaret(-2, 20)
        assertEquals("L1 alpha\nL2 beta", TextSelection.selectedText(emu, sel))
    }

    @Test
    fun `rowRangeText trims trailing blanks`() {
        val emu = newEmu()
        emu.feed("abc   \r\n")
        assertEquals("abc", TextSelection.rowRangeText(emu, 0, 0, 19))
    }

    @Test
    fun `auto-wrapped rows copy as one line`() {
        val emu = TerminalEmulator(10, 5)
        // 25 chars through a 10-col screen: wraps 0→1→2 automatically
        emu.feed("abcdefghijklmnopqrstuvwxy")
        val sel = TextSelection()
        sel.startAnchor(0, 0)
        sel.moveCaret(2, 4)
        assertEquals("abcdefghijklmnopqrstuvwxy", TextSelection.selectedText(emu, sel))
    }

    @Test
    fun `wrapped join also works across the scrollback boundary`() {
        val emu = TerminalEmulator(10, 2)
        // 15 chars wrap across two rows; a following newline pushes the
        // wrapped first row into scrollback (external -1), its continuation
        // becomes screen row 0
        emu.feed("abcdefghijklmno\r\nnext")
        assertEquals(1, emu.scrollbackSize)
        val sel = TextSelection()
        sel.startAnchor(-1, 0)
        sel.moveCaret(0, 9)
        assertEquals("abcdefghijklmno", TextSelection.selectedText(emu, sel))
    }

    @Test
    fun `hard newlines still break lines in the copy`() {
        val emu = newEmu()
        emu.feed("one\r\ntwo")
        val sel = TextSelection()
        sel.startAnchor(0, 0)
        sel.moveCaret(1, 2)
        assertEquals("one\ntwo", TextSelection.selectedText(emu, sel))
    }

    @Test
    fun `clear deactivates`() {
        val sel = TextSelection()
        sel.startAnchor(0, 0)
        sel.moveCaret(1, 1)
        assertTrue(sel.isActive)
        sel.clear()
        assertFalse(sel.isActive)
        assertEquals("", TextSelection.selectedText(newEmu(), sel))
    }
}
