package at.least.conch

/**
 * Column-accurate text selection over the terminal transcript.
 *
 * Coordinates are "external rows": 0..rows-1 = live screen, negative =
 * scrollback (same space the renderer walks, so anchors stay glued to
 * content while the user scrolls). Pure logic — gestures and painting
 * live in TerminalView, JVM tests cover the math.
 *
 * Competitor parity: ConnectBot "Can't copy beyond viewable area" /
 * "Copy full session" — selection reaches into scrollback, not just the
 * visible viewport.
 */
class TextSelection {

    data class Cell(val externalRow: Int, val col: Int)

    var anchor: Cell? = null
        private set

    var caret: Cell? = null
        private set

    val isActive: Boolean get() = anchor != null && caret != null

    fun startAnchor(row: Int, col: Int) {
        anchor = Cell(row, col)
        caret = anchor
    }

    fun moveCaret(row: Int, col: Int) {
        if (anchor != null) caret = Cell(row, col)
    }

    fun clear() {
        anchor = null
        caret = null
    }

    /** Normalized (first, last) ordering regardless of drag direction. */
    fun normalized(): Pair<Cell, Cell>? {
        val a = anchor ?: return null
        val c = caret ?: return null
        return if (a.externalRow < c.externalRow || (a.externalRow == c.externalRow && a.col <= c.col)) {
            a to c
        } else {
            c to a
        }
    }

    /** Rendering predicate: is this cell painted as selected? */
    fun isSelected(externalRow: Int, col: Int): Boolean {
        val (s, e) = normalized() ?: return false
        if (externalRow < s.externalRow || externalRow > e.externalRow) return false
        val from = if (externalRow == s.externalRow) s.col else 0
        val to = if (externalRow == e.externalRow) e.col else Int.MAX_VALUE
        return col in from..to
    }

    companion object {
        /**
         * Selected text. Rows joined by \n — except auto-wrapped rows
         * (DECAWM at the margin), which concatenate onto the next row so a
         * reflowed paragraph copies as one line. Columns are cell-accurate:
         * wide code points count one cell at their start column and are
         * copied whole.
         */
        fun selectedText(emu: TerminalEmulator, sel: TextSelection): String {
            val (s, e) = sel.normalized() ?: return ""
            data class Part(val text: String, val joinedToNext: Boolean)
            val rows = ArrayList<Part>()
            var r = s.externalRow
            while (r <= e.externalRow) {
                if (r < -emu.scrollbackSize || r >= emu.rows) {
                    rows.add(Part("", false))
                } else {
                    val from = if (r == s.externalRow) s.col else 0
                    val to = if (r == e.externalRow) e.col else -1
                    val wraps = r < e.externalRow && emu.isLineWrapped(r)
                    // A wrapped row's trailing cells continue on the next row:
                    // keep them (no trimEnd) so joined content is intact.
                    rows.add(Part(rowRangeText(emu, r, from, to, trim = !wraps), wraps))
                }
                r++
            }
            val sb = StringBuilder()
            for (i in rows.indices) {
                if (i > 0 && !rows[i - 1].joinedToNext) sb.append('\n')
                sb.append(rows[i].text)
            }
            return sb.toString()
        }

        /** Text of [fromCol..toCol] (inclusive) on one external row. */
        fun rowRangeText(
            emu: TerminalEmulator,
            externalRow: Int,
            fromCol: Int,
            toCol: Int,
            trim: Boolean = true,
        ): String {
            val sb = StringBuilder()
            emu.forEachCell(externalRow) { col, cp, _ ->
                if (col >= fromCol && (toCol < 0 || col <= toCol)) sb.appendCodePoint(cp)
            }
            return if (trim) sb.toString().trimEnd() else sb.toString()
        }
    }
}
