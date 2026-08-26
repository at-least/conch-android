package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mouse-protocol contract for the view wiring: DECSET 1000/1002 flip
 * [TerminalEmulator.mouseTracking], sendMouse emits the exact xterm byte
 * sequences (SGR 1006 and legacy X10), and DECRST disables everything.
 * Competitor parity: htop/vim/tmux/Claude-Code users expect tap=click and
 * scroll=wheel inside mouse-tracking apps.
 */
class MouseInputContractTest {

    private fun newEmu(cols: Int = 100, rows: Int = 40) = TerminalEmulator(cols, rows)

    @Test
    fun `DECSET 1000 enables tracking, DECRST disables it`() {
        val emu = newEmu()
        assertFalse(emu.mouseTracking)
        emu.feed("\u001b[?1000h")
        assertTrue(emu.mouseTracking)
        emu.feed("\u001b[?1000l")
        assertFalse(emu.mouseTracking)
    }

    @Test
    fun `DECSET 1002 also enables tracking`() {
        val emu = newEmu()
        emu.feed("\u001b[?1002h")
        assertTrue(emu.mouseTracking)
    }

    @Test
    fun `SGR mode encodes press M and release m with the same button`() {
        val emu = newEmu()
        emu.feed("\u001b[?1000h\u001b[?1006h")
        val out = StringBuilder()
        emu.onResponse = { out.append(String(it)) }

        emu.sendMouse(MouseInput.BUTTON_LEFT, 5, 3, pressed = true)
        emu.sendMouse(MouseInput.BUTTON_LEFT, 5, 3, pressed = false)

        assertEquals("\u001b[<0;5;3M\u001b[<0;5;3m", out.toString())
    }

    @Test
    fun `legacy X10 mode encodes press with button and release as button 3`() {
        val emu = newEmu()
        emu.feed("\u001b[?1000h") // no 1006 → legacy bytes
        val out = StringBuilder()
        emu.onResponse = { out.append(String(it)) }

        emu.sendMouse(MouseInput.BUTTON_LEFT, 5, 3, pressed = true)
        emu.sendMouse(MouseInput.BUTTON_LEFT, 5, 3, pressed = false)

        // ESC [ M 32+button 32+col 32+row ; release rewrites the button to 3
        assertEquals("\u001b[M\u0020\u0025\u0023\u001b[M\u0023\u0025\u0023", out.toString())
    }

    @Test
    fun `SGR wheel events use buttons 64 and 65`() {
        val emu = newEmu()
        emu.feed("\u001b[?1000h\u001b[?1006h")
        val out = StringBuilder()
        emu.onResponse = { out.append(String(it)) }

        emu.sendMouse(MouseInput.BUTTON_WHEEL_UP, 10, 20, pressed = true)
        emu.sendMouse(MouseInput.BUTTON_WHEEL_DOWN, 10, 20, pressed = true)

        assertEquals("\u001b[<64;10;20M\u001b[<65;10;20M", out.toString())
    }

    @Test
    fun `cellAt maps pixels to 1-based cells and clamps to the grid`() {
        val c = MouseInput.cellAt(x = 9.9f, y = 30.1f, cellWidth = 10f, cellHeight = 15f, cols = 80, rows = 24)
        assertEquals(1, c.col)
        assertEquals(3, c.row)

        val clamped = MouseInput.cellAt(x = -50f, y = 9999f, cellWidth = 10f, cellHeight = 15f, cols = 80, rows = 24)
        assertEquals(1, clamped.col)
        assertEquals(24, clamped.row)
    }

    @Test
    fun `wheelButton maps scroll direction`() {
        assertEquals(MouseInput.BUTTON_WHEEL_UP, MouseInput.wheelButton(-2))
        assertEquals(MouseInput.BUTTON_WHEEL_DOWN, MouseInput.wheelButton(3))
    }
}
