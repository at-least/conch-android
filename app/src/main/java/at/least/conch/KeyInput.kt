package at.least.conch

/**
 * Pure transforms for terminal key input — the wire bytes the app sends
 * for the Ctrl latch and special keys (iOS TerminalBridge.applyCtrlLatch /
 * ExtraKeys parity). Extracted from TerminalView so the byte contracts
 * are JVM-testable.
 */
object KeyInput {

    /**
     * Ctrl-latch transform, byte-identical to TerminalView's behavior:
     * a single a–z letter while armed becomes its C0 control byte and
     * consumes the latch; anything else passes through unchanged and the
     * latch state is preserved. NOTE the preserved-state half is the iOS
     * C42 stuck-latch shape — a non-letter key does NOT release the
     * latch, so a later letter fires as Ctrl-letter (fix gated by PLAN
     * H4/B3; the CtrlLatchTest pin moves with that fix).
     *
     * @return bytes to send, and whether the latch is armed afterwards
     */
    fun applyCtrlLatch(armed: Boolean, text: String): Pair<ByteArray, Boolean> {
        if (armed) {
            val lower = text.lowercase()
            if (lower.length == 1 && lower[0] in 'a'..'z') {
                return byteArrayOf((lower[0].code - 'a'.code + 1).toByte()) to false
            }
        }
        return text.toByteArray(Charsets.UTF_8) to armed
    }

    /**
     * Alt-latch transform (xterm meta): a single-character input while
     * armed is prefixed with ESC and consumes the latch; anything else
     * passes through with the latch preserved (same shape as Ctrl).
     */
    fun applyAltLatch(armed: Boolean, bytes: ByteArray): Pair<ByteArray, Boolean> {
        if (armed && String(bytes, Charsets.UTF_8).length == 1) {
            return byteArrayOf(0x1B) + bytes to false
        }
        return bytes to armed
    }

    /** Wire bytes for a special key code; null when the code is unknown. */
    fun keyBytes(code: Int): ByteArray? = when (code) {
        TerminalView.KEY_ESCAPE -> byteArrayOf(0x1B)
        TerminalView.KEY_TAB -> byteArrayOf(0x09)
        TerminalView.KEY_ARROW_UP -> "\u001b[A".toByteArray()
        TerminalView.KEY_ARROW_DOWN -> "\u001b[B".toByteArray()
        TerminalView.KEY_ARROW_RIGHT -> "\u001b[C".toByteArray()
        TerminalView.KEY_ARROW_LEFT -> "\u001b[D".toByteArray()
        TerminalView.CTRL_ARROW_UP -> "\u001b[1;5A".toByteArray()
        TerminalView.CTRL_ARROW_DOWN -> "\u001b[1;5B".toByteArray()
        TerminalView.CTRL_ARROW_RIGHT -> "\u001b[1;5C".toByteArray()
        TerminalView.CTRL_ARROW_LEFT -> "\u001b[1;5D".toByteArray()
        TerminalView.KEY_PAGE_UP -> "\u001b[5~".toByteArray()
        TerminalView.KEY_PAGE_DOWN -> "\u001b[6~".toByteArray()
        TerminalView.KEY_HOME -> "\u001b[H".toByteArray()
        TerminalView.KEY_END -> "\u001b[F".toByteArray()
        TerminalView.KEY_DELETE -> "\u001b[3~".toByteArray()
        TerminalView.KEY_F1 -> "\u001bOP".toByteArray()
        TerminalView.KEY_F2 -> "\u001bOQ".toByteArray()
        TerminalView.KEY_F3 -> "\u001bOR".toByteArray()
        TerminalView.KEY_F4 -> "\u001bOS".toByteArray()
        TerminalView.KEY_F5 -> "\u001b[15~".toByteArray()
        TerminalView.KEY_F6 -> "\u001b[17~".toByteArray()
        TerminalView.KEY_F7 -> "\u001b[18~".toByteArray()
        TerminalView.KEY_F8 -> "\u001b[19~".toByteArray()
        TerminalView.KEY_F9 -> "\u001b[20~".toByteArray()
        TerminalView.KEY_F10 -> "\u001b[21~".toByteArray()
        TerminalView.KEY_F11 -> "\u001b[23~".toByteArray()
        TerminalView.KEY_F12 -> "\u001b[24~".toByteArray()
        else -> null
    }
}
