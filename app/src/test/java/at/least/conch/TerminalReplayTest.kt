package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay-shaped terminal tests (iOS TerminalReplayTests parity, adapted):
 * feed the emulator the byte stream a real PTY produces around the tmux
 * attach and assert what lands in the rendered buffers.
 *
 * PINNED DIVERGENCE: Android sends the BARE attach line (no iOS C53/C54
 * `command -v` guard + printf wipe — PLAN B2/H3). Consequence pinned
 * below: the PTY echo of the attach command stays in the normal buffer
 * (residue). When B2 lands, the wipe bytes clear it — flip that assertion
 * in the same change.
 */
class TerminalReplayTest {

    private fun newEmu(cols: Int = 60, rows: Int = 10) = TerminalEmulator(cols, rows)

    @Test
    fun `fresh attach renders the pane through the alternate screen`() {
        val emu = newEmu()
        // what the PTY actually delivers on connect + tmuxAutoAttach:
        emu.feed("user@srv ~ % ") // prompt
        emu.feed("COLORTERM=truecolor tmux new -A -s conch\r\n") // PTY echo of our line
        emu.feed("\u001b[?1049h\u001b[H\u001b[2J") // tmux enters alt screen, clears
        emu.feed("CONCH-FIX1\r\n") // pane content

        // active (alt) screen: marker visible, nothing else
        assertEquals("CONCH-FIX1", emu.getRowText(0).trim())
    }

    @Test
    fun `attach echo stays in the normal buffer (residue, B2 must flip)`() {
        val emu = newEmu()
        emu.feed("user@srv ~ % ")
        emu.feed("COLORTERM=truecolor tmux new -A -s conch\r\n")
        emu.feed("\u001b[?1049h\u001b[H\u001b[2JCONCH-FIX1\r\n")

        // CURRENT behavior: alt screen hides it, but leaving tmux (1049l)
        // reveals the echoed attach line still sitting in the normal buffer
        emu.feed("\u001b[?1049l")
        val normalText = (0 until 3).map { emu.getRowText(it) }.joinToString(" ")
        assertTrue(
            "bare attach leaves echo residue in the normal buffer — " +
                "B2's printf wipe must clear it (then flip this pin)",
            normalText.contains("tmux new -A -s conch"),
        )
    }

    @Test
    fun `reconnect attach redraws over the existing pane with markers`() {
        val emu = newEmu()
        // first visit leaves CONCH-R1 in the tmux pane (server-side state)
        emu.feed("\u001b[?1049h\u001b[H\u001b[2JCONCH-R1\r\n")
        // reconnect: attach line echo goes to the alt screen bottom, tmux
        // redraws the pane (CONCH-R1 persists) and the fresh shell adds R2
        emu.feed("COLORTERM=truecolor tmux new -A -s conch\r\n")
        emu.feed("\u001b[H\u001b[2JCONCH-R1\r\nCONCH-R2\r\n")

        val screen = (0 until 3).map { emu.getRowText(it).trim() }
        assertTrue("existing-session marker persists", screen.contains("CONCH-R1"))
        assertTrue("fresh marker renders", screen.contains("CONCH-R2"))
    }
}
