package at.least.conch

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator as Engine
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalRow
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/**
 * VT100 / xterm terminal emulator facade over the vendored Termux engine
 * (com.termux.terminal, see app/src/main/java/com/termux/terminal/VENDOR.md).
 *
 * Conch keeps its own renderer and input paths; this facade exposes the
 * engine through the surface conch's view, activity and tests consume:
 * feeding PTY bytes, resize, cursor/scrollback queries, row text and a
 * per-cell walk for rendering. Device responses the engine produces
 * (cursor-position reports, primary-device attributes, ...) surface on
 * [onResponse] and must be written to the SSH channel by the host.
 */
class TerminalEmulator(cols: Int, rows: Int) {

    companion object {
        /** Scrollback history kept for the main screen. */
        const val MAX_SCROLLBACK = 4000

        fun isWide(cp: Int): Boolean = WcWidth.width(cp) == 2
    }

    /** No-op client: conch drives the engine headless (no local TerminalSession). */
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(session: com.termux.terminal.TerminalSession) {}
        override fun onTitleChanged(session: com.termux.terminal.TerminalSession) {}
        override fun onSessionFinished(session: com.termux.terminal.TerminalSession) {}
        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
        override fun onBell(session: com.termux.terminal.TerminalSession) {}
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: com.termux.terminal.TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, msg: String?) {}
        override fun logWarn(tag: String?, msg: String?) {}
        override fun logInfo(tag: String?, msg: String?) {}
        override fun logDebug(tag: String?, msg: String?) {}
        override fun logVerbose(tag: String?, msg: String?) {}
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    /** Engine -> host callbacks (title, bell, device responses). */
    private inner class HostOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            onResponse?.invoke(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String) {
            titleListener?.invoke(newTitle)
        }

        override fun onCopyTextToClipboard(text: String) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() = bellListener?.invoke() ?: Unit
        override fun onColorsChanged() {}
    }

    val engine = Engine(HostOutput(), cols, rows, 0, 0, MAX_SCROLLBACK, client)

    val cols: Int get() = engine.mColumns
    val rows: Int get() = engine.mRows

    var bellListener: (() -> Unit)? = null
    var titleListener: ((String) -> Unit)? = null

    /** Device-originated bytes (DSR/CPR/DA replies) to write to the SSH channel. */
    var onResponse: ((ByteArray) -> Unit)? = null

    val cursorCol: Int get() = engine.getCursorCol()
    val cursorRow: Int get() = engine.getCursorRow()
    val cursorVisible: Boolean get() = engine.shouldCursorBeVisible()

    /** DECSET 2004: remote app asked for bracketed paste markers. */
    val bracketedPasteMode: Boolean get() = engine.isBracketedPasteMode()

    /** Lines of scrollback history held for the main screen. */
    val scrollbackSize: Int get() = screen.getActiveTranscriptRows()

    fun clearScrollback() = screen.clearTranscript()

    private val screen: TerminalBuffer get() = engine.getScreen()

    // ------------------------------------------------------------------ API

    fun feed(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        engine.append(b, b.size)
    }

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        engine.append(
            if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len),
            len,
        )
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        engine.resize(newCols, newRows, 0, 0)
    }

    /**
     * Terminal row in engine coordinates: 0..rows-1 is the live screen,
     * negative indices walk back into scrollback (-1 = line just above
     * the screen). Blank rows materialize on demand, mirroring upstream.
     */
    fun row(externalRow: Int): TerminalRow =
        screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow))

    /**
     * Walks every occupied cell of [externalRow], handing the visit callback
     * the starting column, code point and 64-bit cell style (see
     * [TextStyle]). Wide code points occupy their starting column only
     * (column advances by their wcwidth); combining marks are folded into
     * the preceding cell and not visited, matching upstream's renderer.
     */
    fun forEachCell(externalRow: Int, visit: (col: Int, codePoint: Int, style: Long) -> Unit) {
        val r = row(externalRow)
        val text = r.mText
        val used = r.spaceUsed
        var i = 0
        var col = 0
        while (i < used && col < cols) {
            val c = text[i]
            val cp: Int
            val adv: Int
            if (Character.isHighSurrogate(c)) {
                cp = Character.toCodePoint(c, text[i + 1])
                adv = 2
            } else {
                cp = c.code
                adv = 1
            }
            i += adv
            val w = WcWidth.width(cp)
            if (w <= 0) continue
            visit(col, cp, r.getStyle(col))
            col += w
        }
    }

    fun getRowText(row: Int): String = rowText(row(row))

    fun getScrollbackRowText(line: Int): String = rowText(row(line - scrollbackSize))

    fun getScreenText(): String = (0 until rows).joinToString("\n") { getRowText(it) }

    /** Full text incl. scrollback (newest screen last); used by replay pins. */
    fun getTranscriptText(): String {
        val sb = StringBuilder()
        val sbSize = scrollbackSize
        for (l in 0 until sbSize) {
            sb.append(getScrollbackRowText(l)).append('\n')
        }
        sb.append(getScreenText())
        return sb.toString()
    }

    // ------------------------------------------------------------- internal

    private fun rowText(r: TerminalRow): String {
        val sb = StringBuilder(r.spaceUsed)
        val text = r.mText
        val used = r.spaceUsed
        var i = 0
        while (i < used) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                val cp = Character.toCodePoint(c, text[i + 1])
                if (WcWidth.width(cp) > 0) sb.appendCodePoint(cp)
                i += 2
            } else {
                if (WcWidth.width(c.code) > 0) sb.append(c)
                i++
            }
        }
        return sb.toString().trimEnd()
    }
}
