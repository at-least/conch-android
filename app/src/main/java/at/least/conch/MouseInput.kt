package at.least.conch

/**
 * Pure helpers translating touch gestures into xterm mouse-protocol
 * coordinates. 1-based cell math per DECSET 1000/1006; button codes are
 * the vendored engine's MOUSE_* constants (0 left, 32 left-drag,
 * 64 wheel-up, 65 wheel-down). JVM-tested without a View.
 */
object MouseInput {

    const val BUTTON_LEFT = 0
    const val BUTTON_LEFT_MOVED = 32
    const val BUTTON_WHEEL_UP = 64
    const val BUTTON_WHEEL_DOWN = 65

    data class Cell(val col: Int, val row: Int)

    /** View pixel position -> 1-based protocol cell, clamped to the grid. */
    fun cellAt(x: Float, y: Float, cellWidth: Float, cellHeight: Float, cols: Int, rows: Int): Cell {
        val col = ((x / cellWidth).toInt() + 1).coerceIn(1, cols.coerceAtLeast(1))
        val row = ((y / cellHeight).toInt() + 1).coerceIn(1, rows.coerceAtLeast(1))
        return Cell(col, row)
    }

    /**
     * Wheel button for accumulated scroll lines: negative lines = wheel up
     * (history backwards), positive = wheel down.
     */
    fun wheelButton(lines: Int): Int =
        if (lines < 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
}
