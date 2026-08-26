package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TerminalScroll — pins the scrollback gesture directions. The sign traps:
 * GestureDetector's distanceY is previous-minus-current (finger up positive)
 * while velocityY is the finger's velocity (finger up negative), so the two
 * handlers need opposite arithmetic for the same physical motion. Platform
 * convention: finger down reveals older history; finger up returns to the
 * live screen — matching the mouse branch (finger up = wheel down = newer).
 */
class TerminalScrollTest {

    private val cell = 40f
    private val max = 4000

    @Test
    fun `drag down goes deeper into history`() {
        val next = TerminalScroll.afterDrag(current = 0, distanceY = -3 * cell, cellHeight = cell, max = max)
        assertEquals(3, next)
    }

    @Test
    fun `drag up returns toward the live screen`() {
        val next = TerminalScroll.afterDrag(current = 5, distanceY = 2 * cell, cellHeight = cell, max = max)
        assertEquals(3, next)
    }

    @Test
    fun `drag clamps at both ends`() {
        assertEquals(0, TerminalScroll.afterDrag(2, 10 * cell, cell, max))
        assertEquals(max, TerminalScroll.afterDrag(max - 2, -10 * cell, cell, max))
    }

    @Test
    fun `sub-cell drag is a no-op`() {
        assertEquals(7, TerminalScroll.afterDrag(7, cell / 4, cell, max))
        assertEquals(7, TerminalScroll.afterDrag(7, 0f, cell, max))
    }

    @Test
    fun `fling down goes deeper into history`() {
        // velocityY is the finger velocity: down is positive.
        val next = TerminalScroll.afterFling(current = 0, velocityY = 16000f, max = max)
        assertEquals(2, next)
    }

    @Test
    fun `fling up returns toward the live screen`() {
        val next = TerminalScroll.afterFling(current = 5, velocityY = -16000f, max = max)
        assertEquals(3, next)
    }

    @Test
    fun `slow fling below threshold is a no-op`() {
        assertEquals(7, TerminalScroll.afterFling(7, 1999f, max))
    }

    @Test
    fun `fling clamps at the scrollback ceiling`() {
        assertEquals(max, TerminalScroll.afterFling(max - 1, 800000f, max))
        assertEquals(0, TerminalScroll.afterFling(1, -800000f, max))
    }

    @Test
    fun `wheel lines accumulate remainders across gestures`() {
        // Finger up (distanceY > 0) = wheel down = positive lines.
        val (l1, r1) = TerminalScroll.wheelLines(0f, 1.5f * cell, cell)
        assertEquals(1, l1)
        assertEquals(0.5f * cell, r1, 0.01f)

        val (l2, r2) = TerminalScroll.wheelLines(r1, 0.5f * cell, cell)
        assertEquals(1, l2)
        assertEquals(0f, r2, 0.01f)

        val (l3, _) = TerminalScroll.wheelLines(0f, -2.5f * cell, cell)
        assertEquals(-2, l3)
    }
}
