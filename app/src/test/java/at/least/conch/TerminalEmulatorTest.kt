package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private fun newEmu(cols: Int = 10, rows: Int = 5): TerminalEmulator = TerminalEmulator(cols, rows)

    @Test
    fun `plain text wraps at right margin`() {
        val emu = newEmu(cols = 5, rows = 3)
        emu.feed("ABCDEFG")
        // 5 cols: first row "ABCDE", rest wraps onto second row
        assertEquals("ABCDE", emu.getRowText(0))
        assertEquals("FG", emu.getRowText(1))
    }

    @Test
    fun `newline and carriage return`() {
        val emu = newEmu()
        emu.feed("ab\ncd")
        // \n moves down but keeps column; 'c' lands at col 2 of row 1
        assertEquals("ab", emu.getRowText(0))
        assertEquals("  cd", emu.getRowText(1))
        emu.feed("\rX")
        assertEquals("X cd".trimEnd(), emu.getRowText(1).trimEnd())
    }

    @Test
    fun `backspace moves cursor back`() {
        val emu = newEmu()
        emu.feed("abc\b\bZ")
        assertEquals("aZc", emu.getRowText(0))
    }

    @Test
    fun `cursor position CUP and report via fields`() {
        val emu = newEmu(cols = 10, rows = 5)
        emu.feed("\u001b[3;5H") // 1-based -> row 2, col 4
        assertEquals(4, emu.cursorCol)
        assertEquals(2, emu.cursorRow)
        emu.feed("X")
        assertEquals('X', emu.getCharAt(2, 4))
    }

    @Test
    fun `ED mode 2 clears screen`() {
        val emu = newEmu()
        emu.feed("hello world")
        emu.feed("\u001b[2J")
        assertEquals("", emu.getRowText(0))
        assertEquals("", emu.getRowText(1))
    }

    @Test
    fun `EL mode 0 clears to end of line`() {
        val emu = newEmu()
        emu.feed("abcdef")
        emu.feed("\u001b[1;1H\u001b[3C") // cursor at col 3 (0-based)
        emu.feed("\u001b[K")
        assertEquals("abc", emu.getRowText(0))
    }

    @Test
    fun `CUU CUD CUF CUB move cursor`() {
        val emu = newEmu(cols = 10, rows = 5)
        emu.feed("\u001b[4;6H")   // row 3, col 5
        emu.feed("\u001b[2A")     // up 2 -> row 1
        emu.feed("\u001b[1D")     // left 1 -> col 4
        emu.feed("\u001b[3B")     // down 3 -> row 4
        emu.feed("\u001b[2C")     // right 2 -> col 6
        assertEquals(6, emu.cursorCol)
        assertEquals(4, emu.cursorRow)
    }

    @Test
    fun `LF at bottom scrolls region up`() {
        val emu = newEmu(cols = 5, rows = 3)
        emu.feed("one\r\ntwo\r\nthree\r\n")
        assertEquals("two", emu.getRowText(0))
        assertEquals("three", emu.getRowText(1))
        assertEquals("", emu.getRowText(2))
    }

    @Test
    fun `DECSTBM confines scrolling to region`() {
        val emu = newEmu(cols = 5, rows = 5)
        emu.feed("L1\r\nL2\r\nL3\r\nL4\r\nL5")
        emu.feed("\u001b[2;4r")   // region rows 2..4 (1-based)
        emu.feed("\u001b[4;1H")   // bottom of region
        emu.feed("\r\n")           // scrolls only inside region: row2<-row3<-row4
        assertEquals("L1", emu.getRowText(0))
        assertEquals("L3", emu.getRowText(1))
        assertEquals("L4", emu.getRowText(2))
        assertEquals("", emu.getRowText(3))
        assertEquals("L5", emu.getRowText(4))
    }

    @Test
    fun `ICH inserts blanks at cursor`() {
        val emu = newEmu(cols = 6, rows = 2)
        emu.feed("ABCDEF")
        emu.feed("\u001b[1;2H") // col 1
        emu.feed("\u001b[2@")   // A _ _ B C D (E,F pushed off)
        assertEquals("A  BCD", emu.getRowText(0))
    }

    @Test
    fun `DCH deletes chars at cursor`() {
        val emu = newEmu(cols = 6, rows = 2)
        emu.feed("ABCDEF")
        emu.feed("\u001b[1;2H") // col 1
        emu.feed("\u001b[2P")   // A D E F
        assertEquals("ADEF", emu.getRowText(0))
    }

    @Test
    fun `wide CJK char occupies two cells`() {
        val emu = newEmu(cols = 6, rows = 2)
        emu.feed("한국어")
        assertEquals(6, emu.cursorCol)
        val leadFlags = TerminalEmulator.styleFlags(emu.getStyleAt(0, 0))
        val contFlags = TerminalEmulator.styleFlags(emu.getStyleAt(0, 1))
        assertTrue(leadFlags and TerminalEmulator.FLAG_WIDE != 0)
        assertTrue(contFlags and TerminalEmulator.FLAG_WIDE_CONT != 0)
        assertEquals("한국어", emu.getRowText(0).trimEnd())
    }

    @Test
    fun `utf8 bytes decode to chars`() {
        val emu = newEmu(cols = 10, rows = 2)
        val bytes = "hi こん".toByteArray(Charsets.UTF_8)
        emu.feed(bytes)
        assertEquals("hi こん", emu.getRowText(0).trimEnd())
    }

    @Test
    fun `invalid utf8 yields replacement char`() {
        val emu = newEmu(cols = 10, rows = 2)
        emu.feed(byteArrayOf(0x41, 0xFF.toByte(), 0x42))
        assertEquals("A\uFFFDB", emu.getRowText(0).trimEnd())
    }

    @Test
    fun `SGR colors are stored in style`() {
        val emu = newEmu()
        emu.feed("\u001b[31mR\u001b[0m\u001b[1;44mW")
        val red = emu.getStyleAt(0, 0)
        assertEquals(1, red and 0x1FF)                 // fg = color 1
        assertEquals(TerminalEmulator.BG_DEFAULT, (red shr 9) and 0x1FF)
        val blueBg = emu.getStyleAt(0, 1)
        assertEquals(4, (blueBg shr 9) and 0x1FF)      // bg = color 4
        assertTrue(blueBg shr 18 and TerminalEmulator.FLAG_BOLD != 0)
    }

    @Test
    fun `SGR 256-color and reset`() {
        val emu = newEmu()
        emu.feed("\u001b[38;5;196mX")
        assertEquals(196, emu.getStyleAt(0, 0) and 0x1FF)
        emu.feed("\u001b[0mY")
        assertEquals(TerminalEmulator.FG_DEFAULT, emu.getStyleAt(0, 1) and 0x1FF)
    }

    @Test
    fun `alt screen 1049 saves and restores main screen`() {
        val emu = newEmu(cols = 10, rows = 3)
        emu.feed("main")
        emu.feed("\u001b[?1049h")
        assertEquals("", emu.getRowText(0))
        emu.feed("alt")
        emu.feed("\u001b[?1049l")
        assertEquals("main", emu.getRowText(0))
    }

    @Test
    fun `reverse video flag set and cleared`() {
        val emu = newEmu()
        emu.feed("\u001b[7mA\u001b[27mB")
        assertTrue(emu.getStyleAt(0, 0) shr 18 and TerminalEmulator.FLAG_REVERSE != 0)
        assertEquals(0, emu.getStyleAt(0, 1) shr 18 and TerminalEmulator.FLAG_REVERSE)
    }

    @Test
    fun `OSC title captured`() {
        val emu = newEmu()
        var title = ""
        emu.titleListener = { title = it }
        emu.feed("\u001b]0;my title\u0007")
        assertEquals("my title", title)
        emu.feed("\u001b]2;second\u001b\\")
        assertEquals("second", title)
    }

    @Test
    fun `scroll up and down SU SD`() {
        val emu = newEmu(cols = 4, rows = 3)
        emu.feed("aaaa\r\nbbbb\r\ncccc")
        emu.feed("\u001b[1S") // scroll up 1
        assertEquals("bbbb", emu.getRowText(0))
        emu.feed("\u001b[1T") // scroll down 1
        assertEquals("", emu.getRowText(0))
        assertEquals("bbbb", emu.getRowText(1))
    }

    @Test
    fun `resize preserves content`() {
        val emu = newEmu(cols = 4, rows = 2)
        emu.feed("abcd\r\nefgh")
        emu.resize(6, 3)
        assertEquals("abcd", emu.getRowText(0).trimEnd())
        assertEquals("efgh", emu.getRowText(1).trimEnd())
        emu.feed("XY") // xterm clears the wrap-pending flag on resize -> X overwrites 'h'
        assertEquals("efgXY", emu.getRowText(1).trimEnd())
    }

    @Test
    fun `ESC 7 8 save restore cursor`() {
        val emu = newEmu(cols = 10, rows = 3)
        emu.feed("abc\u001b7\r\n\r\n\u001b8Z")
        assertEquals("abcZ", emu.getRowText(0).trimEnd())
    }

    @Test
    fun `bell listener fires`() {
        val emu = newEmu()
        var rang = false
        emu.bellListener = { rang = true }
        emu.feed("a\u0007b")
        assertTrue(rang)
        assertEquals("ab", emu.getRowText(0).trimEnd())
    }

    @Test
    fun `tab moves to next multiple of 8`() {
        val emu = newEmu(cols = 20, rows = 2)
        emu.feed("ab\tX")
        assertEquals('X', emu.getCharAt(0, 8))
    }

    @Test
    fun `EL mode 1 clears start of line to cursor`() {
        val emu = newEmu()
        emu.feed("abcdef")
        emu.feed("\u001b[1;4H\u001b[1K") // cursor col 3, clear 0..3
        assertEquals("ef", emu.getRowText(0).trimEnd().let { it.takeLast(2) })
        assertTrue(emu.getRowText(0).startsWith("    "))
    }

    @Test
    fun `ECH erases n chars in place`() {
        val emu = newEmu(cols = 8, rows = 2)
        emu.feed("abcdefgh")
        emu.feed("\u001b[1;2H\u001b[3X")
        assertEquals("a   efgh", emu.getRowText(0))
    }

    @Test
    fun `RI reverse index scrolls down at top`() {
        val emu = newEmu(cols = 4, rows = 2)
        emu.feed("bot")
        emu.feed("\u001bM") // move up; at top -> scroll down
        assertEquals("", emu.getRowText(0))
        assertEquals("bot", emu.getRowText(1).trimEnd())
    }

    @Test
    fun `OSC terminated by ESC plus next sequence is still processed`() {
        val emu = newEmu(cols = 10, rows = 3)
        var title = ""
        emu.titleListener = { title = it }
        // OSC title ended by ESC (not ST), immediately followed by CSI 2J
        emu.feed("\u001b]0;mytitle\u001b[2JX")
        assertEquals("mytitle", title)
        // screen must have been cleared and X printed at origin
        assertEquals("X", emu.getRowText(0))
        assertEquals("", emu.getRowText(1))
    }

    @Test
    fun `LF below scroll region does not scroll the region`() {
        val emu = newEmu(cols = 5, rows = 5)
        emu.feed("L1\r\nL2\r\nL3\r\nL4\r\nL5")
        emu.feed("\u001b[2;4r")       // region rows 2..4 (1-based)
        emu.feed("\u001b[5;1H")       // cursor below the region, at screen bottom
        emu.feed("\r\n")              // already on the last row: stays there
        // region contents untouched
        assertEquals("L2", emu.getRowText(1))
        assertEquals("L3", emu.getRowText(2))
        assertEquals("L4", emu.getRowText(3))
        assertEquals("L5", emu.getRowText(4))
        assertEquals(4, emu.cursorRow)
        emu.feed("Z")
        // LF keeps the column (\r reset it to 0), so Z overwrites the L
        assertEquals("Z5", emu.getRowText(4).trimEnd())
    }

    @Test
    fun `wrap below scroll region does not scroll the region`() {
        val emu = newEmu(cols = 4, rows = 5)
        emu.feed("\u001b[2;4r")       // region rows 2..4
        emu.feed("\u001b[5;1HAAAA B") // fills the bottom row, wraps in place
        // wrapped output overwrites the same bottom row; region untouched
        assertEquals(" BAA", emu.getRowText(4))
        assertEquals("", emu.getRowText(1).trimEnd())
    }
}
