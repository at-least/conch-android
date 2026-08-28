package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Differential test of the app's [TerminalEmulator] against a real terminal.
 * tmux is a correct, widely-trusted VT implementation; its `capture-pane -p`
 * is the golden screen. The SAME raw byte stream is:
 *
 *   1. rendered by tmux in an 80x24 pane on the matrix container and captured,
 *   2. fed to the app's emulator, whose [TerminalEmulator.getScreenText] is read,
 *
 * and the two screens must agree cell-for-cell. This catches cursor-addressing,
 * autowrap, erase and overwrite bugs no hand-written golden would think to
 * cover, because tmux — not the test author — decides the expected screen.
 *
 * Plain (`-p`, no `-e`) capture compares CHARACTER PLACEMENT only, so colour
 * rendering differences are deliberately out of scope here (attributes have
 * their own unit tests). Same opt-in as [DockerSshdAuthTest].
 */
class DockerTmuxOracleTest {

    private val esc = "\u001b"

    /** A screen exercising clear, absolute addressing, overwrite and autowrap. */
    private fun placementStream(): ByteArray {
        val sb = StringBuilder()
        sb.append("$esc[2J$esc[H") // clear + home
        sb.append("ABC") // 1,1
        sb.append("$esc[1;40H").append("MIDDLE") // jump to row 1 col 40
        sb.append("$esc[3;1H").append("row three") // row 3
        // autowrap: 90 chars from row 5 col 1 spill onto row 6
        sb.append("$esc[5;1H")
        repeat(90) { sb.append(('a' + (it % 26))) }
        // overwrite part of row 3 (proves erase/overwrite, not append)
        sb.append("$esc[3;1H").append("ROW") // "ROW three"
        // erase to end of line from row 1 col 43 (drops the tail of MIDDLE)
        sb.append("$esc[1;43H$esc[K")
        // park the cursor somewhere harmless bottom-left
        sb.append("$esc[24;1H")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun appScreen(stream: ByteArray): List<String> {
        val emu = TerminalEmulator(80, 24)
        emu.feed(stream)
        return (0 until 24).map { emu.getRowText(it).trimEnd() }
    }

    /** Render [stream] in a fresh 80x24 tmux pane on the container and capture it. */
    private fun tmuxScreen(stream: ByteArray): List<String> {
        val b64 = java.util.Base64.getEncoder().encodeToString(stream)
        val name = "oracle_${System.nanoTime()}"
        // write the exact bytes to a file, then run `cat` as the pane's own
        // command (no interactive shell → no prompt/echo pollution) and hold
        // the rendered screen open with sleep while we capture it.
        DockerMatrix.dockerExec("printf %s '$b64' | base64 -d > /tmp/$name.vt")
        DockerMatrix.dockerExec(
            "tmux kill-session -t $name 2>/dev/null; " +
                "tmux new-session -d -s $name -x 80 -y 24 \"sh -c 'cat /tmp/$name.vt; sleep 8'\"",
        )
        // give cat+tmux a beat to render before capturing
        Thread.sleep(600)
        val raw = try {
            DockerMatrix.dockerExec("tmux capture-pane -p -t $name")
        } finally {
            DockerMatrix.dockerExec("tmux kill-session -t $name 2>/dev/null; rm -f /tmp/$name.vt", allowFailure = true)
        }
        // capture-pane emits up to 24 lines, trailing blank lines omitted —
        // normalise to exactly 24 rstripped rows so both sides line up
        val lines = raw.split("\n").dropLastWhile { it.isEmpty() }.map { it.trimEnd() }
        return (0 until 24).map { lines.getOrElse(it) { "" } }
    }

    @Test(timeout = 60_000)
    fun `the emulator screen matches real tmux for cursor addressing autowrap and erase`() {
        DockerMatrix.requireMatrix()
        val stream = placementStream()
        val expected = tmuxScreen(stream)
        val actual = appScreen(stream)
        assertEquals(
            "emulator screen diverged from tmux:\n--- tmux ---\n${expected.joinToString("\n")}\n" +
                "--- app ---\n${actual.joinToString("\n")}",
            expected,
            actual,
        )
    }
}
