package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ctrl latch + special-key byte contracts (iOS CtrlKeyTransformTests /
 * CtrlLatchLifecycleTests / CtrlComboTests parity, adapted).
 *
 * ADAPTED-BEHAVIOR PIN: iOS's C42 fix releases the latch on ANY keystroke.
 * Android's current latch survives non-letter input (stuck-latch shape) —
 * that behavior is pinned here as-is so the H4/B3 fix must deliberately
 * flip this test, never drift past it.
 */
class CtrlLatchTest {

    private fun bytes(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test
    fun `every letter maps to its C0 control byte and consumes the latch`() {
        for (c in 'a'..'z') {
            val (out, armed) = KeyInput.applyCtrlLatch(true, c.toString())
            assertArrayEquals("$c -> ${c.code - 96}", bytes(c.code - 96), out)
            assertEquals("latch consumed for $c", false, armed)
        }
    }

    @Test
    fun `mapping is case insensitive`() {
        val (out, armed) = KeyInput.applyCtrlLatch(true, "C")
        assertArrayEquals(bytes(0x03), out)
        assertEquals(false, armed)
    }

    @Test
    fun `etx eot sub and flow control contracts`() {
        // iOS pins: Ctrl+C/D/Z; XOFF/XON flow control
        assertArrayEquals(bytes(0x03), KeyInput.applyCtrlLatch(true, "c").first)
        assertArrayEquals(bytes(0x04), KeyInput.applyCtrlLatch(true, "d").first)
        assertArrayEquals(bytes(0x1A), KeyInput.applyCtrlLatch(true, "z").first)
        assertArrayEquals(bytes(0x13), KeyInput.applyCtrlLatch(true, "s").first) // XOFF
        assertArrayEquals(bytes(0x11), KeyInput.applyCtrlLatch(true, "q").first) // XON
    }

    @Test
    fun `non-letter passes through unchanged and latch STAYS armed (C42 pinned)`() {
        // current Android behavior — the stuck-latch bug iOS fixed in C42.
        // Fix is gated by PLAN H4/B3; this pin must move WITH that fix.
        val (out, armed) = KeyInput.applyCtrlLatch(true, " ")
        assertArrayEquals(bytes(' '.code), out)
        assertTrue("latch survives non-letter (bug, see H4/B3)", armed)
    }

    @Test
    fun `multi-byte input passes through and keeps the latch armed`() {
        val (out, armed) = KeyInput.applyCtrlLatch(true, "中")
        assertArrayEquals("中".toByteArray(Charsets.UTF_8), out)
        assertTrue(armed)
    }

    @Test
    fun `disarmed latch passes bytes through unchanged`() {
        val (out, armed) = KeyInput.applyCtrlLatch(false, "c")
        assertArrayEquals(bytes('c'.code), out)
        assertEquals(false, armed)
    }

    @Test
    fun `multi-letter text is not ctrl-transformed`() {
        val (out, armed) = KeyInput.applyCtrlLatch(true, "cat")
        assertArrayEquals("cat".toByteArray(Charsets.UTF_8), out)
        assertTrue(armed)
    }
}

class CtrlComboTest {

    private fun s(code: Int) = KeyInput.keyBytes(code)?.toString(Charsets.UTF_8)

    @Test
    fun `ctrl arrows inject the 1-5 modifier (xterm contract)`() {
        assertEquals("\u001b[1;5A", s(TerminalView.CTRL_ARROW_UP))
        assertEquals("\u001b[1;5B", s(TerminalView.CTRL_ARROW_DOWN))
        assertEquals("\u001b[1;5C", s(TerminalView.CTRL_ARROW_RIGHT))
        assertEquals("\u001b[1;5D", s(TerminalView.CTRL_ARROW_LEFT))
    }

    @Test
    fun `plain arrows and navigation keys are xterm standard`() {
        assertEquals("\u001b[A", s(TerminalView.KEY_ARROW_UP))
        assertEquals("\u001b[B", s(TerminalView.KEY_ARROW_DOWN))
        assertEquals("\u001b[C", s(TerminalView.KEY_ARROW_RIGHT))
        assertEquals("\u001b[D", s(TerminalView.KEY_ARROW_LEFT))
        assertEquals("\u001b[5~", s(TerminalView.KEY_PAGE_UP))
        assertEquals("\u001b[6~", s(TerminalView.KEY_PAGE_DOWN))
        assertEquals("\u001b[H", s(TerminalView.KEY_HOME))
        assertEquals("\u001b[F", s(TerminalView.KEY_END))
        assertEquals("\u001b[3~", s(TerminalView.KEY_DELETE))
        assertEquals("\u001b", s(TerminalView.KEY_ESCAPE))
        assertEquals("\t", s(TerminalView.KEY_TAB))
    }

    @Test
    fun `function keys use SS3 and CSI forms`() {
        assertEquals("\u001bOP", s(TerminalView.KEY_F1))
        assertEquals("\u001bOS", s(TerminalView.KEY_F4))
        assertEquals("\u001b[15~", s(TerminalView.KEY_F5))
        assertEquals("\u001b[24~", s(TerminalView.KEY_F12))
    }

    @Test
    fun `unknown code emits nothing`() {
        assertNull(KeyInput.keyBytes(9999))
    }

    @Test
    fun `alt latch prefixes single chars with esc and consumes`() {
        val (bytes, still) = KeyInput.applyAltLatch(true, "a".toByteArray())
        assertEquals("\u001ba", String(bytes))
        assertFalse(still)
    }

    @Test
    fun `alt latch passes multi-char input through and stays armed`() {
        val (bytes, still) = KeyInput.applyAltLatch(true, "abc".toByteArray())
        assertEquals("abc", String(bytes))
        assertTrue(still)
    }

    @Test
    fun `alt latch inactive is a no-op`() {
        val (bytes, still) = KeyInput.applyAltLatch(false, "a".toByteArray())
        assertEquals("a", String(bytes))
        assertFalse(still)
    }
}
