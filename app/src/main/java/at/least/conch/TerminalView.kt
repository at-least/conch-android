package at.least.conch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import com.termux.terminal.TextStyle
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

    /** Bytes typed by the user (to be written to the SSH channel). */
    var onData: ((ByteArray) -> Unit)? = null

    /**
     * Sink for bytes the VIEW generates on the user's behalf (ZMODEM
     * frames, transfer cancels): they must reach the SSH channel but are
     * not keystrokes, so they must stay out of command history. Falls
     * back to [onData] when unset.
     */
    var onProtocol: ((ByteArray) -> Unit)? = null

    private fun sendProtocol(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        (onProtocol ?: onData)?.invoke(bytes)
    }

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

    /** Alt latch (xterm meta): next single-char input gets an ESC prefix. */
    var altArmed = false
        set(value) {
            field = value
            onAltStateChanged?.invoke(value)
        }

    /**
     * Per-instance colors. Start as a copy of the shared defaults so themes
     * never mutate the companion [PALETTE]. Indices 256/257/258 are the
     * default-fg / default-bg / cursor slots (TextStyle color indices).
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
            palette[COLOR_INDEX_DEFAULT_FG] = defaultFgColor
            palette[COLOR_INDEX_DEFAULT_BG] = bgColor
            setBackgroundColor(bgColor)
            invalidate()
        }

    /** Notifies UI (e.g. Compose key row) that the Ctrl-armed state changed. */
    var onCtrlStateChanged: ((Boolean) -> Unit)? = null

    /** Same for the Alt latch — without it the ALT button stayed lit after the view consumed the latch. */
    var onAltStateChanged: ((Boolean) -> Unit)? = null

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

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (abs(distanceY) <= abs(distanceX)) return false
            val emu = emulator ?: return false
            // Mouse-tracking apps (htop/vim/tmux): scrolling is wheel input
            // for the app, not local scrollback.
            if (emu.mouseTracking) {
                val (lines, remainder) = TerminalScroll.wheelLines(wheelRemainder, distanceY, cellHeight)
                if (lines != 0) {
                    wheelRemainder = remainder
                    val cell = MouseInput.cellAt(e2.x, e2.y, cellWidth, cellHeight, emu.cols, emu.rows)
                    val button = MouseInput.wheelButton(lines)
                    repeat(abs(lines)) { emu.sendMouse(button, cell.col, cell.row, true) }
                }
                return true
            }
            val (next, carry) = TerminalScroll.afterDrag(
                scrollOffset,
                dragRemainder,
                distanceY,
                cellHeight,
                emu.scrollbackSize,
            )
            dragRemainder = carry
            if (next != scrollOffset) {
                scrollOffset = next
                invalidate()
            }
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            dragRemainder = 0f
            return super.onDown(e)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val emu = emulator
            if (emu != null && emu.mouseTracking) {
                // tap = left click (press + release); the soft keyboard stays
                // reachable via the session toolbar button
                val cell = MouseInput.cellAt(e.x, e.y, cellWidth, cellHeight, emu.cols, emu.rows)
                emu.sendMouse(MouseInput.BUTTON_LEFT, cell.col, cell.row, true)
                emu.sendMouse(MouseInput.BUTTON_LEFT, cell.col, cell.row, false)
                return true
            }
            if (selection.isActive) {
                val chip = chipRect
                if (chip != null && chip.contains(e.x, e.y)) {
                    copySelection()
                } else {
                    // any other tap dismisses the selection
                    selection.clear()
                    chipRect = null
                    invalidate()
                }
                return true
            }
            performClick()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            mouseDragActive = false
            val emu = emulator ?: return
            if (emu.mouseTracking) {
                // long-press + move = button-event drag (DECSET 1002; the
                // engine suppresses it when only 1000 is active)
                mouseDragActive = true
                dragCell = MouseInput.cellAt(e.x, e.y, cellWidth, cellHeight, emu.cols, emu.rows)
                emu.sendMouse(MouseInput.BUTTON_LEFT, dragCell!!.col, dragCell!!.row, true)
                return
            }
            // text selection: anchor in external (content-glued) coordinates
            selecting = true
            chipRect = null
            selection.startAnchor(externalRowAt(e.y), colAt(e.x))
            invalidate()
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val emu = emulator ?: return false
            if (emu.mouseTracking) {
                // wheel burst, capped so a flick cannot flood the channel
                val lines = (velocityY / 4000f).roundToInt().coerceIn(-10, 10)
                if (lines != 0) {
                    val cell = MouseInput.cellAt(e2.x, e2.y, cellWidth, cellHeight, emu.cols, emu.rows)
                    val button = MouseInput.wheelButton(lines)
                    repeat(abs(lines)) { emu.sendMouse(button, cell.col, cell.row, true) }
                }
                return true
            }
            // simple fling: consume remaining velocity as history lines
            val next = TerminalScroll.afterFling(scrollOffset, velocityY, emu.scrollbackSize)
            if (next != scrollOffset) {
                scrollOffset = next
                invalidate()
            }
            return true
        }
    }

    private val gestureDetector = GestureDetector(context, gestureListener)

    /** Sub-cell remainder carried between onScroll wheel conversions. */
    private var wheelRemainder = 0f

    /** Sub-cell carry between scrollback drag events (see TerminalScroll.afterDrag). */
    private var dragRemainder = 0f

    /** Long-press drag armed while a mouse-tracking app wants drags. */
    private var mouseDragActive = false
    private var dragCell: MouseInput.Cell? = null

    /** Active text selection (long-press + drag) and its Copy chip. */
    private val selection = TextSelection()
    private var selecting = false
    private var chipRect: RectF? = null
    private val selectionPaint = Paint().apply { color = 0x4080DEEA.toInt() }
    private val chipPaint = Paint().apply { color = 0xEE264F78.toInt() }
    private val chipTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }

    /** Returns the URL under the given view coordinates, if any. */
    private fun urlAt(x: Float, y: Float): String? {
        val emu = emulator ?: return null
        val col = ((x - paddingLeft) / cellWidth).toInt().coerceIn(0, emu.cols - 1)
        val vi = ((y - paddingTop) / cellHeight).toInt()
        val externalRow = vi - scrollOffset
        if (externalRow >= emu.rows) return null
        return urlInRow(emu, externalRow, col)
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
        val gridChanged = cols != lastSentCols || rows != lastSentRows
        if (cols > 0 && rows > 0 && gridChanged) {
            lastSentCols = cols
            lastSentRows = rows
            onPtyResize?.invoke(cols, rows)
        }
    }

    fun desiredColumns(widthPx: Int): Int = ((widthPx - paddingLeft - paddingRight) / cellWidth).toInt().coerceAtLeast(
        8
    )
    fun desiredRows(heightPx: Int): Int = ((heightPx - paddingTop - paddingBottom) / cellHeight).toInt().coerceAtLeast(
        4
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGrid()
        invalidate()
    }

    private fun recomputeGrid() {
        val emu = emulator ?: return // grid applied when an emulator attaches
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
        val tx = zmodemTx
        if (tx != null) {
            // upload in progress: all bytes belong to the sender engine
            val res = tx.feed(data)
            sendProtocol(res.send)
            for (e in res.events) senderEvent(e)
        } else {
            // one receiver for the life of the view: it returns to sniffing
            // after every finished/failed download, so it keeps watching
            // for the next `sz` and never swallows the shell's output
            val zm = zmodemRx ?: ZmodemReceiver().also { zmodemRx = it }
            val res = zm.feed(data)
            sendProtocol(res.send)
            for (e in res.events) zmodemEvent(e)
            if (res.display.isNotEmpty()) emulator?.feed(res.display)
        }
        if (!repaintScheduled) {
            repaintScheduled = true
            postOnAnimation {
                repaintScheduled = false
                invalidate()
            }
        }
    }

    /**
     * ZMODEM both ways: `sz` on the remote is detected in the output stream
     * and file bytes are routed to the sink; `rz` on the remote raises
     * [ZmodemSink.onZmodemUploadRequested] and a picked file is pushed via
     * [beginZmodemUpload]. Protocol replies go back over the SSH channel.
     * Parity driver: Termius's most-reacted feature request (rz/sz).
     */
    interface ZmodemSink {
        fun onZmodemOffer(name: String, size: Long)
        fun onZmodemData(chunk: ByteArray)
        fun onZmodemComplete(name: String, size: Long)
        fun onZmodemFailed(reason: String)

        /** Remote `rz` is waiting for us to pick a file to send. */
        fun onZmodemUploadRequested()
    }

    private var zmodemRx: ZmodemReceiver? = null
    private var zmodemTx: ZmodemSender? = null
    var zmodemSink: ZmodemSink? = null

    /** Pushes the SAF-picked file into a pending upload (after onZmodemUploadRequested). */
    fun beginZmodemUpload(name: String, bytes: ByteArray) {
        val tx = zmodemTx ?: return
        sendProtocol(tx.begin(name, bytes))
    }

    /** Aborts any in-flight transfer in either direction; harmless when idle. */
    fun cancelZmodem() {
        var had = false
        zmodemRx?.let {
            if (it.isActive) {
                sendProtocol(it.cancel())
                had = true
            }
        }
        zmodemTx?.let {
            sendProtocol(it.cancel())
            had = true
        }
        zmodemRx = null
        zmodemTx = null
        if (had) emulator?.feed("\u001b[90m[zmodem] cancelled\u001b[0m\r\n")
    }

    private fun zmodemEvent(e: ZmodemReceiver.Event) {
        when (e) {
            is ZmodemReceiver.Event.Started -> Unit
            is ZmodemReceiver.Event.UploadRequested -> {
                zmodemRx = null
                zmodemTx = ZmodemSender().also { it.adoptRemoteZrinit(e.canFc32) }
                emulator?.feed("\r\n\u001b[90m[zmodem] remote rz — pick a file to send\u001b[0m\r\n")
                zmodemSink?.onZmodemUploadRequested()
            }
            is ZmodemReceiver.Event.Offered -> {
                emulator?.feed("\r\n\u001b[90m[zmodem] receiving ${e.name} (${e.size} bytes)\u001b[0m\r\n")
                zmodemSink?.onZmodemOffer(e.name, e.size)
            }
            is ZmodemReceiver.Event.Data -> zmodemSink?.onZmodemData(e.chunk)
            is ZmodemReceiver.Event.Complete -> {
                emulator?.feed("\u001b[90m[zmodem] done — ${e.name} (${e.size} bytes)\u001b[0m\r\n")
                zmodemSink?.onZmodemComplete(e.name, e.size)
            }
            is ZmodemReceiver.Event.Failed -> {
                emulator?.feed("\u001b[90m[zmodem] failed: ${e.reason}\u001b[0m\r\n")
                zmodemSink?.onZmodemFailed(e.reason)
            }
        }
    }

    private fun senderEvent(e: ZmodemSender.Event) {
        when (e) {
            is ZmodemSender.Event.Ready -> Unit
            is ZmodemSender.Event.Progress ->
                emulator?.feed("\u001b[90m[zmodem] sent ${e.sent}/${e.total} bytes\u001b[0m\r\n")
            is ZmodemSender.Event.Complete -> {
                emulator?.feed("\u001b[90m[zmodem] sent ${e.name} — done\u001b[0m\r\n")
                zmodemTx = null
            }
            is ZmodemSender.Event.Skipped -> zmodemTx = null
            is ZmodemSender.Event.Failed -> {
                emulator?.feed("\u001b[90m[zmodem] send failed: ${e.reason}\u001b[0m\r\n")
                zmodemTx = null
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

        val boldSaved = textPaint.isFakeBoldText
        val underlineSaved = textPaint.isUnderlineText

        for (vi in 0 until visibleRows) {
            val externalRow = vi - scrollOffset
            if (externalRow >= emu.rows) break
            val y = paddingTop + vi * cellHeight + fontAscent

            emu.forEachCell(externalRow) { col, cp, style ->
                val effect = TextStyle.decodeEffect(style)
                val x = paddingLeft + col * cellWidth
                val widthCells = if (TerminalEmulator.isWide(cp)) 2 else 1

                var fg = resolveColor(TextStyle.decodeForeColor(style))
                var bg = resolveColor(TextStyle.decodeBackColor(style))
                if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) {
                    val t = fg
                    fg = bg
                    bg = t
                }
                if (bg != bgColor) {
                    bgPaint.color = bg
                    canvas.drawRect(x, y - fontAscent, x + cellWidth * widthCells, y - fontAscent + cellHeight, bgPaint)
                }

                if (cp != ' '.code && effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
                    textPaint.isFakeBoldText = effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0
                    textPaint.isUnderlineText = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
                    textPaint.color = fg
                    canvas.drawText(String(Character.toChars(cp)), x, y, textPaint)
                }
            }
        }
        textPaint.isFakeBoldText = boldSaved
        textPaint.isUnderlineText = underlineSaved

        drawSelection(canvas, emu, visibleRows)

        if (emu.cursorVisible && scrollOffset == 0) {
            val cx = paddingLeft + emu.cursorCol * cellWidth
            val cy = paddingTop + emu.cursorRow * cellHeight
            cursorPaint.color = 0x6680DEEA.toInt()
            canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint)
        }

        if (scrollOffset > 0) {
            // thin indicator: how deep into history we are
            val frac = if (emu.scrollbackSize > 0) scrollOffset.toFloat() / emu.scrollbackSize else 0f
            val barH = height * (visibleRows.toFloat() / (visibleRows + scrollOffset)).coerceIn(0.05f, 1f)
            val barY = (height - barH) * frac
            canvas.drawRect(width - 6f, barY, width - 2f, barY + barH, scrollPaint)
        }
    }

    /**
     * Resolves a decoded cell color: truecolor values carry their own argb;
     * palette indices (0..255, plus the 256/257 default slots) map through
     * the themed [palette].
     */
    private fun resolveColor(decoded: Int): Int =
        if (decoded and 0xFF000000.toInt() != 0) decoded else palette[decoded]

    /** Selection overlay: one translucent rect per visible selected row-span,
     *  plus the floating Copy chip next to the caret. */
    private fun drawSelection(canvas: Canvas, emu: TerminalEmulator, visibleRows: Int) {
        if (!selection.isActive) return
        val (s, e) = selection.normalized() ?: return
        for (vi in 0 until visibleRows) {
            val externalRow = vi - scrollOffset
            if (externalRow in s.externalRow..e.externalRow && externalRow < emu.rows) {
                val from = if (externalRow == s.externalRow) s.col else 0
                val to = (if (externalRow == e.externalRow) e.col else emu.cols - 1).coerceAtMost(emu.cols - 1)
                if (to >= from) {
                    val left = paddingLeft + from * cellWidth
                    val top = paddingTop + vi * cellHeight
                    canvas.drawRect(
                        left,
                        top,
                        left + (to - from + 1) * cellWidth,
                        top + cellHeight,
                        selectionPaint,
                    )
                }
            }
        }
        chipRect?.let { chip ->
            canvas.drawRoundRect(chip, 8f, 8f, chipPaint)
            chipTextPaint.textSize = cellHeight * 0.9f
            val ty = chip.centerY() - (chipTextPaint.descent() + chipTextPaint.ascent()) / 2f
            canvas.drawText("  Copy  ", chip.left, ty, chipTextPaint)
        }
    }

    // ----------------------------------------------------------------- input

    /**
     * "Activate this view" for both a finger tap and an accessibility service
     * (TalkBack double-tap, ACTION_CLICK): leave scrollback if we are in it,
     * otherwise raise the soft keyboard. onSingleTapConfirmed routes its plain
     * tap through here so the two paths can never drift apart — a custom view
     * that handles touches without a performClick is unreachable to a11y
     * (ClickableViewAccessibility).
     */
    override fun performClick(): Boolean {
        super.performClick()
        if (scrollOffset > 0) {
            scrollOffset = 0
            invalidate()
        } else {
            showSoftKeyboard()
        }
        return true
    }

    // Clicks ARE routed to performClick — from onSingleTapConfirmed, which is
    // the only place that can tell a tap from the start of a drag, a
    // long-press selection or a scroll. Lint only looks for the call inside
    // onTouchEvent itself and cannot see through the GestureDetector.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (mouseDragActive) forwardMouseDrag(event)
        if (selecting) forwardSelectionDrag(event)
        val superHandled = super.onTouchEvent(event)
        return superHandled || handled
    }

    private fun forwardMouseDrag(event: MotionEvent) {
        val emu = emulator ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val cell = MouseInput.cellAt(event.x, event.y, cellWidth, cellHeight, emu.cols, emu.rows)
                if (cell != dragCell) {
                    dragCell = cell
                    emu.sendMouse(MouseInput.BUTTON_LEFT_MOVED, cell.col, cell.row, true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragCell?.let { emu.sendMouse(MouseInput.BUTTON_LEFT, it.col, it.row, false) }
                mouseDragActive = false
                dragCell = null
            }
        }
    }

    private fun forwardSelectionDrag(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                selection.moveCaret(externalRowAt(event.y), colAt(event.x))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selecting = false
                onSelectionGestureEnd(event.x, event.y)
                invalidate()
            }
        }
    }

    private fun externalRowAt(y: Float): Int =
        ((y - paddingTop) / cellHeight).toInt() - scrollOffset

    private fun colAt(x: Float): Int = ((x - paddingLeft) / cellWidth).toInt()

    /** Selection gesture released: single-cell tap keeps the old URL copy;
     *  a real drag arms the Copy chip next to the caret. */
    private fun onSelectionGestureEnd(x: Float, y: Float) {
        val emu = emulator ?: return
        val (a, c) = selection.normalized() ?: return
        if (a == c) {
            // legacy one-tap behavior: long-press a URL copies the URL
            val url = urlAt(x, y)
            if (url != null) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                android.widget.Toast.makeText(context, "URL copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            selection.clear()
            chipRect = null
            return
        }
        chipRect = computeChipRect(emu, c)
    }

    private fun computeChipRect(emu: TerminalEmulator, caret: TextSelection.Cell): RectF {
        val text = "  Copy  "
        val tw = textPaint.measureText(text)
        val th = cellHeight * 1.4f
        val vi = (caret.externalRow + scrollOffset).coerceIn(0, emu.rows - 1)
        // A viewport narrower than the chip text (tiny font + split screen)
        // would make coerceIn(min > max) throw; clamp instead.
        val maxX = (width - tw).coerceAtLeast(paddingLeft.toFloat())
        val cx = (paddingLeft + (caret.col + 1) * cellWidth).coerceIn(paddingLeft.toFloat(), maxX)
        var top = paddingTop + vi * cellHeight - th
        if (top < paddingTop) top = paddingTop + (vi + 1) * cellHeight
        return RectF(cx, top, cx + tw, top + th)
    }

    private fun copySelection() {
        val emu = emulator ?: return
        val text = TextSelection.selectedText(emu, selection)
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            android.widget.Toast
                .makeText(context, "Copied ${text.length} chars", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        selection.clear()
        chipRect = null
        invalidate()
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
        altArmed = false
        if (emulator?.bracketedPasteMode == true) {
            sendRaw(BracketedPaste.wrap(text).toByteArray(Charsets.UTF_8))
        } else {
            sendRaw(BracketedPaste.sanitize(text).toByteArray(Charsets.UTF_8))
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        resetScrollOnInput()
        val (ctrlBytes, ctrlStill) = KeyInput.applyCtrlLatch(ctrlArmed, text)
        ctrlArmed = ctrlStill
        val (bytes, altStill) = KeyInput.applyAltLatch(altArmed, ctrlBytes)
        altArmed = altStill
        onData?.invoke(bytes)
    }

    fun sendKey(code: Int) {
        resetScrollOnInput()
        KeyInput.keyBytes(code)?.let { onData?.invoke(it) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = emulator ?: return super.onKeyDown(keyCode, event)
        resetScrollOnInput()
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                onData?.invoke(byteArrayOf(0x0D))
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                // DEL (0x7F), the PTY's default `erase` — what Termux and
                // ConnectBot send. BS (0x08) only works in readline; `sudo`
                // password prompts, `read`, `cat`, `less` insert a literal ^H
                onData?.invoke(byteArrayOf(0x7F))
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                onData?.invoke("\u001b[3~".toByteArray())
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onData?.invoke(byteArrayOf(0x1B))
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                onData?.invoke(byteArrayOf(0x09))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_UP else KEY_ARROW_UP)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_DOWN else KEY_ARROW_DOWN)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_LEFT else KEY_ARROW_LEFT)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendKey(if (event.isCtrlPressed) CTRL_ARROW_RIGHT else KEY_ARROW_RIGHT)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                sendKey(KEY_PAGE_UP)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                sendKey(KEY_PAGE_DOWN)
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                sendKey(KEY_HOME)
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                sendKey(KEY_END)
                return true
            }
            KeyEvent.KEYCODE_F1 -> {
                sendKey(KEY_F1)
                return true
            }
            KeyEvent.KEYCODE_F2 -> {
                sendKey(KEY_F2)
                return true
            }
            KeyEvent.KEYCODE_F3 -> {
                sendKey(KEY_F3)
                return true
            }
            KeyEvent.KEYCODE_F4 -> {
                sendKey(KEY_F4)
                return true
            }
            KeyEvent.KEYCODE_F5 -> {
                sendKey(KEY_F5)
                return true
            }
            KeyEvent.KEYCODE_F6 -> {
                sendKey(KEY_F6)
                return true
            }
            KeyEvent.KEYCODE_F7 -> {
                sendKey(KEY_F7)
                return true
            }
            KeyEvent.KEYCODE_F8 -> {
                sendKey(KEY_F8)
                return true
            }
            KeyEvent.KEYCODE_F9 -> {
                sendKey(KEY_F9)
                return true
            }
            KeyEvent.KEYCODE_F10 -> {
                sendKey(KEY_F10)
                return true
            }
            KeyEvent.KEYCODE_F11 -> {
                sendKey(KEY_F11)
                return true
            }
            KeyEvent.KEYCODE_F12 -> {
                sendKey(KEY_F12)
                return true
            }
            KeyEvent.KEYCODE_SPACE ->
                if (event.isCtrlPressed) {
                    onData?.invoke(byteArrayOf(0))
                    return true
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
                if (BracketedPaste.looksLikePaste(text.toString())) {
                    view.pasteText(text.toString())
                } else {
                    view.sendText(text.toString())
                }
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
         * Finds the URL covering [col] on the given engine row (0..rows-1 is
         * the live screen, negative walks into scrollback), accounting for
         * wide chars occupying two columns.
         */
        fun urlInRow(emu: TerminalEmulator, externalRow: Int, col: Int): String? {
            val sb = StringBuilder()
            // column per UTF-16 unit of [sb]: Matcher offsets are char
            // indices, and an astral code point (emoji) is two of them —
            // indexing per CELL shifted every URL after one by a column
            val colOf = ArrayList<Int>()
            emu.forEachCell(externalRow) { c, cp, _ ->
                repeat(Character.charCount(cp)) { colOf.add(c) }
                sb.appendCodePoint(cp)
            }
            if (colOf.isEmpty()) return null
            val matcher = URL_REGEX.matcher(sb.toString())
            while (matcher.find()) {
                val startCol = colOf[matcher.start().coerceAtMost(colOf.size - 1)]
                val endCol = colOf[(matcher.end() - 1).coerceAtMost(colOf.size - 1)]
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

        /** TextStyle color indices for the default fg/bg slots. */
        const val COLOR_INDEX_DEFAULT_FG = 256
        const val COLOR_INDEX_DEFAULT_BG = 257

        /**
         * xterm 256-color palette plus the 3 special slots (default fg,
         * default bg, cursor) at indices 256..258.
         */
        val PALETTE = IntArray(259).also { pal ->
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
            pal[COLOR_INDEX_DEFAULT_FG] = DEFAULT_FG_COLOR
            pal[COLOR_INDEX_DEFAULT_BG] = BG_COLOR
            pal[258] = 0xFF80DEEA.toInt()
        }
    }
}
