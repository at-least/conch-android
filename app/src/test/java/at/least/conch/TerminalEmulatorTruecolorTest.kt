package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTruecolorTest {

    @Test
    fun `SGR 38-2 stores exact rgb`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[38;2;255;100;0mX")
        val idx = emu.getStyleAt(0, 0) and 0x1FF
        assertTrue("expected dynamic index >=256, got $idx", idx >= 256)
        assertEquals(0xFF6400, emu.rgbAtIndex(idx))   // 255<<16 | 100<<8 | 0
    }

    @Test
    fun `SGR 48-2 stores exact background rgb`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[48;2;16;200;255mX")
        val idx = (emu.getStyleAt(0, 0) shr 9) and 0x1FF
        assertTrue(idx >= 256)
        assertEquals(0x10C8FF, emu.rgbAtIndex(idx))
    }

    @Test
    fun `same rgb reuses slot`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[38;2;10;20;30mA\u001b[0m\u001b[38;2;10;20;30mB")
        val a = emu.getStyleAt(0, 0) and 0x1FF
        val b = emu.getStyleAt(0, 1) and 0x1FF
        assertEquals(a, b)
    }

    @Test
    fun `mixed palette and truecolor coexist`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[31mR\u001b[38;2;1;2;3mT\u001b[34mB")
        assertEquals(1, emu.getStyleAt(0, 0) and 0x1FF)          // palette red
        assertTrue((emu.getStyleAt(0, 1) and 0x1FF) >= 256)      // truecolor
        assertEquals(4, emu.getStyleAt(0, 2) and 0x1FF)          // palette blue
    }

    @Test
    fun `more than 255 distinct colors fall back to quantization`() {
        val emu = TerminalEmulator(cols = 100, rows = 4)
        // 300 distinct RGB values -> dynamic table (255 slots) fills up
        val sb = StringBuilder()
        for (i in 0 until 300) {
            sb.append("\u001b[38;2;${i % 256};${(i / 256) * 80};${(i * 7) % 256}m")
            sb.append((if (i % 100 == 99) "\r\n" else "."))
        }
        emu.feed(sb.toString())
        // i=0 -> rgb(0,0,0), the first dynamic slot, must resolve exactly
        assertEquals(0x000000, emu.rgbAtIndex(256))
        // chars: row0 = i 0..98, row1 = i 100..198, row2 = i 200..299
        // i=200 is the 201st distinct color -> still dynamic
        assertTrue((emu.getStyleAt(1, 0) and 0x1FF) >= 256)
        // i=256 (col 56 on row2) is the 257th distinct color -> quantized to palette
        val late = emu.getStyleAt(2, 56) and 0x1FF
        assertTrue("expected palette fallback <256, got $late", late < 256)
    }

    @Test
    fun `rgbAtIndex returns null for palette and default`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("\u001b[31mX")
        assertNull(emu.rgbAtIndex(1))
        assertNull(emu.rgbAtIndex(TerminalEmulator.FG_DEFAULT))
        assertNull(emu.rgbAtIndex(300))   // not allocated
    }
}
