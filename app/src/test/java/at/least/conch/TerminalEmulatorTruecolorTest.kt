package at.least.conch

import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Truecolor (SGR 38;2 / 48;2) pins against the vendored Termux engine. */
class TerminalEmulatorTruecolorTest {

    private fun styleAt0(emu: TerminalEmulator, col: Int): Long =
        emu.engine.getScreen().getStyleAt(0, col)

    @Test
    fun `SGR 38-2 stores exact rgb per cell`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[38;2;255;100;0mX")
        assertEquals(0xFFFF6400.toInt(), TextStyle.decodeForeColor(styleAt0(emu, 0)))
    }

    @Test
    fun `SGR 48-2 stores exact background rgb`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[48;2;16;200;255mX")
        assertEquals(0xFF10C8FF.toInt(), TextStyle.decodeBackColor(styleAt0(emu, 0)))
    }

    @Test
    fun `same rgb twice yields identical cell style`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[38;2;10;20;30mA\u001b[0m\u001b[38;2;10;20;30mB")
        assertEquals(styleAt0(emu, 0), styleAt0(emu, 1))
    }

    @Test
    fun `mixed palette and truecolor coexist`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[31mR\u001b[38;2;1;2;3mT\u001b[34mB")
        assertEquals(1, TextStyle.decodeForeColor(styleAt0(emu, 0)))       // palette red
        assertEquals(0xFF010203.toInt(), TextStyle.decodeForeColor(styleAt0(emu, 1))) // truecolor
        assertEquals(4, TextStyle.decodeForeColor(styleAt0(emu, 2)))       // palette blue
    }

    @Test
    fun `many distinct truecolors stay exact (no slot cap)`() {
        val emu = TerminalEmulator(cols = 100, rows = 4)
        val sb = StringBuilder()
        for (i in 0 until 300) {
            sb.append("\u001b[38;2;${i % 256};${(i / 256) * 80};${(i * 7) % 256}m")
            sb.append(if (i % 100 == 99) "\r\n" else ".")
        }
        emu.feed(sb.toString())
        // first cell: rgb(0,0,0) exactly
        assertEquals(0xFF000000.toInt(), TextStyle.decodeForeColor(styleAt0(emu, 0)))
        // row1 first cell is i=100: rgb(100, 0, 700%256=188)
        assertEquals(0xFF6400BC.toInt(), TextStyle.decodeForeColor(emu.engine.getScreen().getStyleAt(1, 0)))
        // last PRINTED cell is i=298 (i=299 emits only \r\n): row2 col98,
        // rgb(298%256=42, 80, 2086%256=38)
        assertEquals(0xFF2A5026.toInt(), TextStyle.decodeForeColor(emu.engine.getScreen().getStyleAt(2, 98)))
    }

    @Test
    fun `default foreground decodes to the special index`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("X")
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(styleAt0(emu, 0)))
    }
}
