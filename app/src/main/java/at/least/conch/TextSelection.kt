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
         * Selected text, rows joined by \n. Columns are cell-accurate:
         * wide code points count one cell at their start column and are
         * copied whole.
         */
        fun selectedText(emu: TerminalEmulator, sel: TextSelection): String {
            val (s, e) = sel.normalized() ?: return ""
            val rows = ArrayList<CharSequence>()
            var r = s.externalRow
            while (r <= e.externalRow) {
                if (r < -emu.scrollbackSize || r >= emu.rows) {
                    rows.add("")
                } else {
                    val from = if (r == s.externalRow) s.col else 0
                    val to = if (r == e.externalRow) e.col else -1
                    rows.add(rowRangeText(emu, r, from, to))
                }
                r++
            }
            return rows.joinToString("\n")
        }

        /** Text of [fromCol..toCol] (inclusive) on one external row. */
        fun rowRangeText(emu: TerminalEmulator, externalRow: Int, fromCol: Int, toCol: Int): String {
            val sb = StringBuilder()
            emu.forEachCell(externalRow) { col, cp, _ ->
                if (col >= fromCol && (toCol < 0 || col <= toCol)) sb.appendCodePoint(cp)
            }
            return sb.toString().trimEnd()
        }
    }
}
