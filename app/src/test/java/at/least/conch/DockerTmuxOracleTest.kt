package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Differential tests of the app's [TerminalEmulator] against a real terminal.
 * tmux is a correct, widely-trusted VT implementation; its `capture-pane -p`
 * is the golden screen. For each scenario the SAME raw byte stream is:
 *
 *   1. rendered by tmux in an 80x24 pane on the matrix container and captured,
 *   2. fed to the app's emulator, whose screen text is read,
 *
 * and the two screens must agree cell-for-cell. This catches cursor
 * addressing, autowrap, scroll regions, the alternate screen, wide (CJK)
 * cells, save/restore, line insert and tab stops — with tmux, not the test
 * author, deciding the expected screen.
 *
 * Plain (`-p`, no `-e`) capture compares CHARACTER PLACEMENT only; colour and
 * other attributes have their own unit tests and are out of scope here. Same
 * opt-in as [DockerSshdAuthTest].
 */
class DockerTmuxOracleTest {

    private val esc = "\u001b"

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

    private fun assertMatchesTmux(scenario: String, stream: ByteArray) {
        DockerMatrix.requireMatrix()
        val expected = tmuxScreen(stream)
        val actual = appScreen(stream)
        assertEquals(
            "[$scenario] emulator screen diverged from tmux:\n--- tmux ---\n${expected.joinToString("\n")}\n" +
                "--- app ---\n${actual.joinToString("\n")}",
            expected,
            actual,
        )
    }

    private fun bytes(build: StringBuilder.() -> Unit): ByteArray =
        StringBuilder().apply(build).toString().toByteArray(Charsets.UTF_8)

    @Test(timeout = 60_000)
    fun `cursor addressing autowrap and erase`() {
        assertMatchesTmux(
            "placement",
            bytes {
                append("$esc[2J$esc[H")
                append("ABC")
                append("$esc[1;40H").append("MIDDLE")
                append("$esc[3;1H").append("row three")
                append("$esc[5;1H")
                repeat(90) { append(('a' + (it % 26))) } // wraps row 5 → row 6
                append("$esc[3;1H").append("ROW") // overwrite start of row 3
                append("$esc[1;43H$esc[K") // erase to EOL drops MIDDLE's tail
                append("$esc[24;1H")
            },
        )
    }

    @Test(timeout = 60_000)
    fun `scroll region scrolls only within its margins`() {
        assertMatchesTmux(
            "scroll-region",
            bytes {
                append("$esc[2J$esc[H")
                append("TOP-LINE") // row 1, outside the region
                append("$esc[2;5r") // DECSTBM: region rows 2..5
                append("$esc[5;1H") // cursor at region bottom
                for (k in 1..8) append("line$k\r\n") // 8 lines through a 4-row region
                append("$esc[r") // reset region
                append("$esc[24;1H")
            },
        )
    }

    @Test(timeout = 60_000)
    fun `alternate screen buffer restores the primary screen on exit`() {
        assertMatchesTmux(
            "alt-screen",
            bytes {
                append("$esc[2J$esc[H").append("PRIMARY-CONTENT-HERE")
                append("$esc[3;1H").append("second primary line")
                append("$esc[?1049h") // enter alternate screen
                append("$esc[2J$esc[H").append("ALTERNATE-SCREEN-TEXT")
                append("$esc[?1049l") // leave → primary restored
                append("$esc[24;1H")
            },
        )
    }

    @Test(timeout = 60_000)
    fun `wide CJK cells occupy two columns`() {
        assertMatchesTmux(
            "wide-cjk",
            bytes {
                append("$esc[2J$esc[H")
                append("[").append("你好世界").append("]") // each CJK glyph is 2 cells wide
                append("$esc[2;1H").append("mix 日本語 abc")
                append("$esc[24;1H")
            },
        )
    }

    @Test(timeout = 60_000)
    fun `save and restore cursor position`() {
        assertMatchesTmux(
            "save-restore",
            bytes {
                append("$esc[2J$esc[H")
                append("$esc[5;10H").append(esc).append("7") // DECSC at 5,10
                append("$esc[10;20H").append("X") // print X elsewhere
                append(esc).append("8").append("Y") // DECRC → back to 5,10, print Y
                append("$esc[24;1H")
            },
        )
    }

    @Test(timeout = 60_000)
    fun `insert line shifts rows down within the screen`() {
        assertMatchesTmux(
            "insert-line",
            bytes {
                append("$esc[2J$esc[H")
                append("AAAA\r\nBBBB\r\nCCCC")
                append("$esc[2;1H") // to row 2
                append("$esc[L") // insert a blank line: BBBB/CCCC move down
                append("NEW") // row 2 becomes NEW
                append("$esc[24;1H")
            },
        )
    }

    @Test(timeout = 60_000)
    fun `horizontal tab stops`() {
        assertMatchesTmux(
            "tabs",
            bytes {
                append("$esc[2J$esc[H")
                append("a\tb\tc\td") // default tab stops every 8 columns
                append("$esc[2;1H").append("12345678\tX") // tab from col 9 → col 17
                append("$esc[24;1H")
            },
        )
    }
}
