package at.least.conch

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure scrollback math for the terminal gesture handlers, JVM-tested without
 * a View. Pinning it matters because GestureDetector's two velocity
 * conventions have OPPOSITE signs for the same physical motion:
 * `onScroll`'s distanceY is previous-minus-current (finger up → positive),
 * while `onFling`'s velocityY is the finger's velocity (finger up →
 * negative). Platform convention (and the mouse-tracking branch that turns
 * the same gestures into wheel events): finger down reveals older history,
 * finger up returns toward the live screen.
 */
object TerminalScroll {

    /** Drag: finger-down (distanceY < 0) scrolls deeper into history. Whole-event variant. */
    fun afterDrag(current: Int, distanceY: Float, cellHeight: Float, max: Int): Int =
        afterDrag(current, 0f, distanceY, cellHeight, max).first

    /**
     * Drag with carry: `onScroll` reports the delta since the LAST event,
     * which at 120 Hz is a few pixels — rounding each one to whole lines
     * left slow drags stuck at zero. The sub-cell remainder is returned and
     * must be fed back on the next event (reset it on finger-down).
     */
    fun afterDrag(current: Int, remainder: Float, distanceY: Float, cellHeight: Float, max: Int): Pair<Int, Float> {
        if (cellHeight <= 0f) return current to remainder
        val acc = remainder + distanceY
        val lines = (acc / cellHeight).toInt()
        if (lines == 0) return current to acc
        return (current - lines).coerceIn(0, max) to (acc - lines * cellHeight)
    }

    /** Fling: consumes the remaining velocity; finger-down fling goes deeper. */
    fun afterFling(current: Int, velocityY: Float, max: Int): Int {
        if (abs(velocityY) <= FLING_THRESHOLD) return current
        val lines = (velocityY / FLING_LINES_PER_VELOCITY).roundToInt()
        return (current + lines).coerceIn(0, max)
    }

    /** Mouse-tracking apps: accumulated distanceY → wheel lines (finger up = wheel down). */
    fun wheelLines(remainder: Float, distanceY: Float, cellHeight: Float): Pair<Int, Float> {
        val acc = remainder + distanceY
        val lines = (acc / cellHeight).toInt()
        return if (lines != 0) lines to (acc - lines * cellHeight) else 0 to acc
    }

    private const val FLING_THRESHOLD = 2000f
    private const val FLING_LINES_PER_VELOCITY = 8000f
}
