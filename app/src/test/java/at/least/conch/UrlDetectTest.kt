package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlDetectTest {

    private fun emuWith(vararg lines: String): TerminalEmulator {
        val emu = TerminalEmulator(cols = 60, rows = lines.size + 1)
        emu.feed(lines.joinToString("\r\n"))
        return emu
    }

    @Test
    fun `plain url detected at any column inside it`() {
        val emu = emuWith("see https://example.com/some/path for docs")
        val li = 0
        // find where the url starts on screen
        val row = emu.getRowText(0)
        val start = row.indexOf("https://")
        val end = start + "https://example.com/some/path".length - 1
        assertEquals("https://example.com/some/path", TerminalView.urlInRow(emu, li, 0, start))
        assertEquals("https://example.com/some/path", TerminalView.urlInRow(emu, li, 0, (start + end) / 2))
        assertEquals("https://example.com/some/path", TerminalView.urlInRow(emu, li, 0, end))
        assertNull(TerminalView.urlInRow(emu, li, 0, 0))   // 's' of "see" is no url
    }

    @Test
    fun `url with port and query`() {
        val emu = emuWith("go to http://10.0.0.5:8080/admin?x=1 now")
        val row = emu.getRowText(0)
        val start = row.indexOf("http://")
        assertEquals("http://10.0.0.5:8080/admin?x=1", TerminalView.urlInRow(emu, 0, 0, start + 3))
    }

    @Test
    fun `www form without scheme detected`() {
        val emu = emuWith("docs at www.example.io/page")
        val row = emu.getRowText(0)
        val start = row.indexOf("www.")
        assertEquals("www.example.io/page", TerminalView.urlInRow(emu, 0, 0, start))
    }

    @Test
    fun `no false positives on plain words`() {
        val emu = emuWith("error: host unreachable port closed")
        assertNull(TerminalView.urlInRow(emu, 0, 0, 3))
        assertNull(TerminalView.urlInRow(emu, 0, 0, 20))
    }

    @Test
    fun `columns account for leading wide chars`() {
        val emu = emuWith("한국어 https://example.com/x end")
        val row = emu.getRowText(0)
        val textIdx = row.indexOf("https://")
        // wide chars occupy 2 columns: text index != column; url starts at
        // column = 1(text-space 0-1) *2 ... compute: cols consumed by text before url
        var colsBefore = 0
        var i = 0
        while (i < textIdx) {
            colsBefore += if (TerminalEmulator.isWide(row[i].code)) 2 else 1
            i++
        }
        assertEquals("https://example.com/x", TerminalView.urlInRow(emu, 0, 0, colsBefore))
        assertEquals("https://example.com/x", TerminalView.urlInRow(emu, 0, 0, colsBefore + 10))
    }

    @Test
    fun `url inside scrollback row found`() {
        val emu = TerminalEmulator(cols = 40, rows = 2)
        emu.feed("https://old.example.com/1\r\nsecond\r\nthird\r\n")
        // rows "…old…" and "second" scrolled into history
        assertEquals(2, emu.scrollbackSize)
        assertEquals("https://old.example.com/1", TerminalView.urlInRow(emu, 0, -1, 2))
        assertNull(TerminalView.urlInRow(emu, 1, -1, 2))
    }
}
