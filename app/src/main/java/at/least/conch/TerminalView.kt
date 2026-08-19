package at.least.conch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Renders a [TerminalEmulator] with a monospace grid and forwards user input
 * (soft keyboard IME, hardware keys, extra-keys row) to the SSH session.
 * Supports scrolling into scrollback history with a vertical drag gesture.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val urlRegex = java.util.regex.Pattern.compile(
        "(https?://|www\\.)[\\w.-]+(:\\d+)?(/[^\\s]*)?",
        java.util.regex.Pattern.CASE_INSENSITIVE
    )

    /** Bytes typed by the user (to be written to the SSH channel). */
    var onData: ((ByteArray) -> Unit)? = null

    /** Terminal grid changed dimensions (cols, rows). */
    var onPtyResize: ((Int, Int) -> Unit)? = null

    /** Fired whenever the scroll position moves (for UI indicators). */
    var onScrollOffsetChanged: ((Int) -> Unit)? = null

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            scrollOffset = 0
            field?.let { applyGridSize(it.cols, it.rows) }
        }

    /** When true, the next letter key is sent as Ctrl+letter. */
    var ctrlArmed = false
        set(value) {
            field = value
            onCtrlStateChanged?.invoke(value)
        }

    /**
     * Per-instance colors. Start as a copy of the shared defaults so themes
     * never mutate the companion [PALETTE].
     */
    private val palette = PALETTE.copyOf()
    private var bgColor = BG_COLOR
    private var defaultFgColor = DEFAULT_FG_COLOR

    /** Applies a [TerminalTheme] and re-renders. */
    var theme: TerminalTheme = TerminalTheme.DEFAULT
        set(value) {
            field = value
            value.base16Into(palette)
            bgColor = (0xFF shl 24) or value.bg
            defaultFgColor = (0xFF shl 24) or value.defaultFg
            setBackgroundColor(bgColor)
            invalidate()
        }

    /** Notifies UI (e.g. Compose key row) that the Ctrl-armed state changed. */
    var onCtrlStateChanged: ((Boolean) -> Unit)? = null

    var fontSizePx: Float = 15f * resources.displayMetrics.scaledDensity
        set(value) {
            field = value.coerceIn(6f * resources.displayMetrics.scaledDensity, 40f * resources.displayMetrics.scaledDensity)
            textPaint.textSize = field
            measureCell()
            requestLayout()
            // The view's own size usually does not change with the font, so
            // onSizeChanged will not fire — recompute the grid explicitly or
            // cols/rows stay stale and columns get clipped/under-filled.
            post { recomputeGrid() }
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSizePx
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint()
    private val scrollPaint = Paint().apply { color = 0x8080DEEA.toInt() }

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var fontAscent = 0f

    /** Rows scrolled back into history; 0 = live screen. */
    var scrollOffset = 0
        private set(value) {
            field = value
            onScrollOffsetChanged?.invoke(value)
        }

    private var lastSentCols = -1
    private var lastSentRows = -1

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (abs(distanceY) <= abs(distanceX)) return false
            val emu = emulator ?: return false
            val lines = (distanceY / cellHeight).roundToInt()
            if (lines != 0) {
                scrollOffset = (scrollOffset + lines).coerceIn(0, emu.scrollbackSize)
                invalidate()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (scrollOffset > 0) {
                scrollOffset = 0
                invalidate()
            } else {
                showSoftKeyboard()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val url = urlAt(e.x, e.y) ?: return
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            android.widget.Toast.makeText(context, "URL copied", android.widget.Toast.LENGTH_SHORT).show()
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            // simple fling: consume remaining velocity as history lines
            if (abs(velocityY) > 2000) {
                val emu = emulator ?: return false
                val lines = (velocityY / 8000f).roundToInt()
                scrollOffset = (scrollOffset - lines).coerceIn(0, emu.scrollbackSize)
                invalidate()
            }
            return true
        }
    })

    /** Returns the URL under the given view coordinates, if any. */
    private fun urlAt(x: Float, y: Float): String? {
        val emu = emulator ?: return null
        val col = ((x - paddingLeft) / cellWidth).toInt().coerceIn(0, emu.cols - 1)
        val visibleRows = ceil((height - paddingTop - paddingBottom) / cellHeight).toInt()
        val total = emu.scrollbackSize + emu.rows
        val first = (total - emu.rows - scrollOffset).coerceAtLeast(0)
        val vi = ((y - paddingTop) / cellHeight).toInt().coerceIn(0, visibleRows - 1)
        val li = first + vi
        if (li >= total) return null
        val screenRow = li - emu.scrollbackSize
        return urlInRow(emu, li, screenRow, col)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCell()
        setBackgroundColor(bgColor)
    }

    private fun measureCell() {
        cellWidth = textPaint.measureText("W")
        val fm = textPaint.fontMetrics
        cellHeight = fm.descent - fm.ascent
        fontAscent = -fm.ascent
        if (cellWidth <= 0f) cellWidth = fontSizePx * 0.6f
        if (cellHeight <= 0f) cellHeight = fontSizePx * 1.2f
    }

    private fun applyGridSize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0 && (cols != lastSentCols || rows != lastSentRows)) {
            lastSentCols = cols
            lastSentRows = rows
            onPtyResize?.invoke(cols, rows)
        }
    }

    fun desiredColumns(widthPx: Int): Int = ((widthPx - paddingLeft - paddingRight) / cellWidth).toInt().coerceAtLeast(8)
    fun desiredRows(heightPx: Int): Int = ((heightPx - paddingTop - paddingBottom) / cellHeight).toInt().coerceAtLeast(4)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGrid()
        invalidate()
    }

    private fun recomputeGrid() {
        val emu = emulator ?: return   // grid applied when an emulator attaches
        val cols = desiredColumns(width)
        val rows = desiredRows(height)
        if (cols != emu.cols || rows != emu.rows) {
            emu.resize(cols, rows)
        }
        scrollOffset = scrollOffset.coerceIn(0, emu.scrollbackSize)
        applyGridSize(cols, rows)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    fun attachEmulator(cols: Int, rows: Int): TerminalEmulator {
        if (emulator == null) {
            emulator = TerminalEmulator(cols, rows)
        }
        return emulator!!
    }

    /**
     * Feeds terminal output and schedules a repaint. Batches of data arriving
     * within one frame collapse into a single invalidate via postOnAnimation,
     * keeping bursts like `cat bigfile` smooth.
     */
    fun feedAndInvalidate(data: ByteArray) {
        emulator?.feed(data)
        if (!repaintScheduled) {
            repaintScheduled = true
            postOnAnimation {
                repaintScheduled = false
                invalidate()
            }
        }
    }

    private var repaintScheduled = false

    // ---------------------------------------------------------------- output

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val emu = emulator ?: return
        canvas.drawColor(bgColor)

        val visibleRows = ceil((height - paddingTop - paddingBottom) / cellHeight).toInt()
        val total = emu.scrollbackSize + emu.rows
        val first = (total - emu.rows - scrollOffset).coerceAtLeast(0)

        val boldSaved = textPaint.isFakeBoldText
        val underlineSaved = textPaint.isUnderlineText

        for (vi in 0 until visibleRows) {
            val li = first + vi
            if (li >= total) break
            val inScrollback = li < emu.scrollbackSize
            val screenRow = li - emu.scrollbackSize
            val y = paddingTop + vi * cellHeight + fontAscent

            for (c in 0 until emu.cols) {
                val style = if (inScrollback) emu.scrollbackStyleAt(li, c)
                else emu.styles[screenRow * emu.cols + c]
                val flags = TerminalEmulator.styleFlags(style)
                if (flags and TerminalEmulator.FLAG_WIDE_CONT != 0) continue
                val x = paddingLeft + c * cellWidth

                var fg = styleToFg(style)
                var bg = styleToBg(style)
                if (flags and TerminalEmulator.FLAG_REVERSE != 0) {
                    val t = fg; fg = bg; bg = t
                }
                if (bg != bgColor) {
                    bgPaint.color = bg
                    canvas.drawRect(x, y - fontAscent, x + cellWidth, y - fontAscent + cellHeight, bgPaint)
                }

                val ch = if (inScrollback) emu.scrollbackCharAt(li, c)
                else emu.chars[screenRow * emu.cols + c]
                if (ch != ' ' && flags and TerminalEmulator.FLAG_INVISIBLE == 0) {
                    textPaint.isFakeBoldText = flags and TerminalEmulator.FLAG_BOLD != 0
                    textPaint.isUnderlineText = flags and TerminalEmulator.FLAG_UNDERLINE != 0
                    textPaint.color = fg
                    canvas.drawText(ch.toString(), x, y, textPaint)
                }
            }
        }
        textPaint.isFakeBoldText = boldSaved
        textPaint.isUnderlineText = underlineSaved

        if (emu.cursorVisible && scrollOffset == 0) {
            val cx = paddingLeft + emu.cursorCol * cellWidth
            val cy = paddingTop + emu.cursorRow * cellHeight
            cursorPaint.color = 0x6680DEEA.toInt()
            canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint)
        }

        if (scrollOffset > 0) {
            // thin indicator: how deep into history we are
            val frac = if (emu.scrollbackSize > 0) scrollOffset.toFloat() / emu.scrollbackSize else 0f
            val barH = height * (visibleRows.toFloat() / total).coerceIn(0.05f, 1f)
            val barY = (height - barH) * frac
            canvas.drawRect(width - 6f, barY, width - 2f, barY + barH, scrollPaint)
        }
    }

    private fun styleToFg(style: Int): Int {
        val idx = style and 0x1FF
        if (idx == TerminalEmulator.FG_DEFAULT) return defaultFgColor
        emulator?.rgbAtIndex(idx)?.let { return 0xFF000000.toInt() or it }
        return palette[idx]
    }

    private fun styleToBg(style: Int): Int {
        val idx = (style shr 9) and 0x1FF
        if (idx == TerminalEmulator.BG_DEFAULT) return bgColor
        emulator?.rgbAtIndex(idx)?.let { return 0xFF000000.toInt() or it }
        return palette[idx]
    }

    // ----------------------------------------------------------------- input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        val superHandled = super.onTouchEvent(event)
        return superHandled || handled
    }

    fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, 0)
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun resetScrollOnInput() {
        if (scrollOffset > 0) {
            scrollOffset = 0
            invalidate()
        }
    }

    /** Sends raw bytes to the SSH channel (extra-keys row). */
    fun sendRaw(bytes: ByteArray) {
        resetScrollOnInput()
        onData?.invoke(bytes)
    }

    /**
     * Pastes [text] into the terminal: bracketed (ESC[200~...ESC[201~) when
     * the remote enabled DECSET 2004, newline-sanitized otherwise. Bypasses
     * [sendText] (never interpreted as Ctrl-shortcut) and clears any armed
     * Ctrl so the next keystroke is not swallowed as Ctrl-letter.
     */
    fun pasteText(text: String) {
        if (text.isEmpty()) return
        ctrlArmed = false
        if (emulator?.bracketedPasteMode == true) {
            sendRaw(BracketedPaste.wrap(text).toByteArray(Charsets.UTF_8))
        } else {
            sendRaw(BracketedPaste.sanitize(text).toByteArray(Charsets.UTF_8))
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        resetScrollOnInput()
        if (ctrlArmed) {
            val lower = text.lowercase()
            if (lower.length == 1 && lower[0] in 'a'..'z') {
                ctrlArmed = false
                onData?.invoke(byteArrayOf((lower[0].code - 'a'.code + 1).toByte()))
                return
            }
        }
        onData?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    fun sendKey(code: Int) {
        resetScrollOnInput()
        when (code) {
            KEY_ESCAPE -> onData?.invoke(byteArrayOf(0x1B))
            KEY_TAB -> onData?.invoke(byteArrayOf(0x09))
            KEY_ARROW_UP -> onData?.invoke("\u001b[A".toByteArray())
            KEY_ARROW_DOWN -> onData?.invoke("\u001b[B".toByteArray())
            KEY_ARROW_RIGHT -> onData?.invoke("\u001b[C".toByteArray())
            KEY_ARROW_LEFT -> onData?.invoke("\u001b[D".toByteArray())
            CTRL_ARROW_UP -> onData?.invoke("\u001b[1;5A".toByteArray())
            CTRL_ARROW_DOWN -> onData?.invoke("\u001b[1;5B".toByteArray())
            CTRL_ARROW_RIGHT -> onData?.invoke("\u001b[1;5C".toByteArray())
            CTRL_ARROW_LEFT -> onData?.invoke("\u001b[1;5D".toByteArray())
            KEY_PAGE_UP -> onData?.invoke("\u001b[5~".toByteArray())
            KEY_PAGE_DOWN -> onData?.invoke("\u001b[6~".toByteArray())
            KEY_HOME -> onData?.invoke("\u001b[H".toByteArray())
            KEY_END -> onData?.invoke("\u001b[F".toByteArray())
            KEY_DELETE -> onData?.invoke("\u001b[3~".toByteArray())
            KEY_F1 -> onData?.invoke("\u001bOP".toByteArray())
            KEY_F2 -> onData?.invoke("\u001bOQ".toByteArray())
            KEY_F3 -> onData?.invoke("\u001bOR".toByteArray())
            KEY_F4 -> onData?.invoke("\u001bOS".toByteArray())
            KEY_F5 -> onData?.invoke("\u001b[15~".toByteArray())
            KEY_F6 -> onData?.invoke("\u001b[17~".toByteArray())
            KEY_F7 -> onData?.invoke("\u001b[18~".toByteArray())
            KEY_F8 -> onData?.invoke("\u001b[19~".toByteArray())
            KEY_F9 -> onData?.invoke("\u001b[20~".toByteArray())
            KEY_F10 -> onData?.invoke("\u001b[21~".toByteArray())
            KEY_F11 -> onData?.invoke("\u001b[23~".toByteArray())
            KEY_F12 -> onData?.invoke("\u001b[24~".toByteArray())
            else -> {}
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = emulator ?: return super.onKeyDown(keyCode, event)
        resetScrollOnInput()
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                onData?.invoke(byteArrayOf(0x0D)); return true
            }
            KeyEvent.KEYCODE_DEL -> {
                onData?.invoke(byteArrayOf(0x08)); return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                onData?.invoke("\u001b[3~".toByteArray()); return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onData?.invoke(byteArrayOf(0x1B)); return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onData?.invoke(byteArrayOf(0x09)); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_UP else KEY_ARROW_UP); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_DOWN else KEY_ARROW_DOWN); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_LEFT else KEY_ARROW_LEFT); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_RIGHT else KEY_ARROW_RIGHT); return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                sendKey(KEY_PAGE_UP); return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                sendKey(KEY_PAGE_DOWN); return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                sendKey(KEY_HOME); return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                sendKey(KEY_END); return true
            }
            KeyEvent.KEYCODE_F1 -> { sendKey(KEY_F1); return true }
            KeyEvent.KEYCODE_F2 -> { sendKey(KEY_F2); return true }
            KeyEvent.KEYCODE_F3 -> { sendKey(KEY_F3); return true }
            KeyEvent.KEYCODE_F4 -> { sendKey(KEY_F4); return true }
            KeyEvent.KEYCODE_F5 -> { sendKey(KEY_F5); return true }
            KeyEvent.KEYCODE_F6 -> { sendKey(KEY_F6); return true }
            KeyEvent.KEYCODE_F7 -> { sendKey(KEY_F7); return true }
            KeyEvent.KEYCODE_F8 -> { sendKey(KEY_F8); return true }
            KeyEvent.KEYCODE_F9 -> { sendKey(KEY_F9); return true }
            KeyEvent.KEYCODE_F10 -> { sendKey(KEY_F10); return true }
            KeyEvent.KEYCODE_F11 -> { sendKey(KEY_F11); return true }
            KeyEvent.KEYCODE_F12 -> { sendKey(KEY_F12); return true }
            KeyEvent.KEYCODE_SPACE ->
                if (event.isCtrlPressed) {
                    onData?.invoke(byteArrayOf(0)); return true
                }
        }
        if (event.isCtrlPressed) {
            val unicode = event.unicodeChar
            val c = when {
                unicode in 0x41..0x5A || unicode in 0x61..0x7A ->
                    Character.toLowerCase(unicode.toChar())
                unicode == 0x00 && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                    ('a' + keyCode - KeyEvent.KEYCODE_A)
                else -> null
            }
            if (c != null && c in 'a'..'z') {
                onData?.invoke(byteArrayOf((c - 'a' + 1).toByte()))
                return true
            }
            if (unicode == 0x00) return super.onKeyDown(keyCode, event)
        }
        val unicode = event.unicodeChar
        if (unicode != 0) {
            sendText(String(Character.toChars(unicode)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val emu = emulator
        if (emu != null && event.isCtrlPressed) {
            val unicode = event.unicodeChar
            if (unicode in 0x41..0x5A || unicode in 0x61..0x7A) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    private class TerminalInputConnection(private val view: TerminalView) : BaseInputConnection(view, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Multi-line IME payloads (clipboard paste gestures) go through the
            // bracketed-paste path; single-line commits are normal typing.
            if (text.isNotEmpty()) {
                if (BracketedPaste.looksLikePaste(text.toString())) view.pasteText(text.toString())
                else view.sendText(text.toString())
            }
            return true
        }
    }

    companion object {
        private val URL_REGEX = java.util.regex.Pattern.compile(
            "(https?://|www\\.)[\\w.-]+(:\\d+)?(/[^\\s]*)?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        )

        /**
         * Finds the URL covering [col] on the given logical row (scrollback or
         * screen), accounting for wide chars occupying two columns.
         */
        fun urlInRow(emu: TerminalEmulator, li: Int, screenRow: Int, col: Int): String? {
            val inScrollback = li < emu.scrollbackSize
            val sb = StringBuilder()
            val colOf = IntArray(emu.cols)
            var idx = 0
            for (c in 0 until emu.cols) {
                val style = if (inScrollback) emu.scrollbackStyleAt(li, c)
                else emu.styles[screenRow * emu.cols + c]
                if (TerminalEmulator.styleFlags(style) and TerminalEmulator.FLAG_WIDE_CONT != 0) continue
                colOf[idx] = c
                sb.append(if (inScrollback) emu.scrollbackCharAt(li, c) else emu.chars[screenRow * emu.cols + c])
                idx++
            }
            if (idx == 0) return null
            val matcher = URL_REGEX.matcher(sb.toString())
            while (matcher.find()) {
                val startCol = colOf[matcher.start().coerceAtMost(idx - 1)]
                val endCol = colOf[(matcher.end() - 1).coerceAtMost(idx - 1)]
                if (col in startCol..endCol) return matcher.group()
            }
            return null
        }

        const val KEY_ESCAPE = 1001
        const val KEY_TAB = 1002
        const val KEY_ARROW_UP = 1003
        const val KEY_ARROW_DOWN = 1004
        const val KEY_ARROW_LEFT = 1005
        const val KEY_ARROW_RIGHT = 1006
        const val KEY_PAGE_UP = 1007
        const val KEY_PAGE_DOWN = 1008
        const val KEY_HOME = 1009
        const val KEY_END = 1010
        const val KEY_DELETE = 1011
        const val KEY_F1 = 1101
        const val KEY_F2 = 1102
        const val KEY_F3 = 1103
        const val KEY_F4 = 1104
        const val KEY_F5 = 1105
        const val KEY_F6 = 1106
        const val KEY_F7 = 1107
        const val KEY_F8 = 1108
        const val KEY_F9 = 1109
        const val KEY_F10 = 1110
        const val KEY_F11 = 1111
        const val KEY_F12 = 1112
        const val CTRL_ARROW_UP = 1201
        const val CTRL_ARROW_DOWN = 1202
        const val CTRL_ARROW_LEFT = 1203
        const val CTRL_ARROW_RIGHT = 1204

        const val BG_COLOR = 0xFF1A1B26.toInt()
        const val DEFAULT_FG_COLOR = 0xFFE0E0E0.toInt()

        /** xterm 256-color palette. */
        val PALETTE = IntArray(256).also { pal ->
            val base16 = intArrayOf(
                0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
                0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
                0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
                0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt(),
            )
            base16.forEachIndexed { i, c -> pal[i] = c }
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            var idx = 16
            for (r in 0..5) for (g in 0..5) for (b in 0..5) {
                pal[idx++] = (0xFF shl 24) or (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
            }
            for (i in 0..23) {
                val v = 8 + i * 10
                pal[idx++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }
}
