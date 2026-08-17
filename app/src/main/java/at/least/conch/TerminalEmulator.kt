package at.least.conch

/**
 * VT100 / xterm escape-sequence terminal emulator (subset), pure Kotlin.
 *
 * Supports: CUD/CUU/CUF/CUB/CUP/CHA/VPA, ED/EL, IL/DL/ICH/DCH/ECH, SU/SD,
 * DECSTBM scroll region, SGR (16/256/RGB colors, bold/dim/reverse/underline),
 * alt-screen (1049/47/1047), save/restore cursor, tabs, UTF-8 (incl. CJK wide chars).
 */
class TerminalEmulator(var cols: Int, var rows: Int) {

    companion object {
        const val FG_DEFAULT = 0x1FF
        const val BG_DEFAULT = 0x1FF

        const val FLAG_BOLD = 1
        const val FLAG_UNDERLINE = 2
        const val FLAG_REVERSE = 4
        const val FLAG_DIM = 8
        const val FLAG_WIDE = 16
        const val FLAG_WIDE_CONT = 32
        const val FLAG_INVISIBLE = 64

        private const val STATE_GROUND = 0
        private const val STATE_ESC = 1
        private const val STATE_CSI = 2
        private const val STATE_OSC = 3
        private const val STATE_OSC_ESC = 4
        private const val STATE_DCS = 5
        private const val STATE_DCS_ESC = 6
        private const val STATE_CHARSET = 7

        const val MAX_SCROLLBACK = 4000

        /** Dynamic truecolor slots: style color field 256..510 (511 = default). */
        const val DYNAMIC_MAX = 255

        fun isWide(cp: Int): Boolean {
            if (cp < 0x1100) return false
            return (cp in 0x1100..0x115F) ||
                (cp in 0x2E80..0xA4CF) ||
                (cp in 0xAC00..0xD7A3) ||
                (cp in 0xF900..0xFAFF) ||
                (cp in 0xFE30..0xFE4F) ||
                (cp in 0xFF00..0xFF60) ||
                (cp in 0xFFE0..0xFFE6) ||
                (cp in 0x1F300..0x1F64F) ||
                (cp in 0x1F900..0x1F9FF) ||
                (cp in 0x20000..0x3FFFD)
        }

        /** 0xRRGGBB values of the standard xterm 256-color palette (for RGB quantization). */
        val PALETTE_RGB: IntArray = IntArray(256).also { pal ->
            val base16 = intArrayOf(
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510,
                0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543,
                0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
            )
            base16.forEachIndexed { i, c -> pal[i] = c }
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            var idx = 16
            for (r in 0..5) for (g in 0..5) for (b in 0..5) {
                pal[idx++] = (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
            }
            for (i in 0..23) {
                val v = 8 + i * 10
                pal[idx++] = (v shl 16) or (v shl 8) or v
            }
        }

        fun nearestPaletteIndex(r: Int, g: Int, b: Int): Int {
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in 0..255) {
                val c = PALETTE_RGB[i]
                val dr = r - (c shr 16 and 0xFF)
                val dg = g - (c shr 8 and 0xFF)
                val db = b - (c and 0xFF)
                val dist = dr * dr + dg * dg + db * db
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            return best
        }

        fun styleFlags(style: Int): Int = style ushr 18
    }

    var chars = CharArray(cols * rows) { ' ' }
    var styles = IntArray(cols * rows)
    var cursorCol = 0
        private set
    var cursorRow = 0
        private set
    var cursorVisible = true

    // scrollback history (main screen only), newest last
    private val sbChars = ArrayDeque<CharArray>()
    private val sbStyles = ArrayDeque<IntArray>()

    /** Dynamic truecolor (RGB) entries; style index 256..510 maps here. */
    private val dynamicRgb = IntArray(DYNAMIC_MAX)
    private var dynamicCount = 0

    val scrollbackSize: Int get() = sbChars.size

    private var savedCol = 0
    private var savedRow = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var pendingWrap = false

    // current SGR state
    private var curFg = FG_DEFAULT
    private var curBg = BG_DEFAULT
    private var curFlags = 0

    // alt screen saved state
    private var altScreen = false
    private var mainChars: CharArray? = null
    private var mainStyles: IntArray? = null
    private var mainCol = 0
    private var mainRow = 0

    // parser state
    private var state = STATE_GROUND
    private val csiParams = StringBuilder(16)
    private var csiPrivate = false

    // incremental UTF-8 decoding
    private var utfAccum = 0
    private var utfNeed = 0

    var bellListener: (() -> Unit)? = null
    var titleListener: ((String) -> Unit)? = null
    private val oscBuffer = StringBuilder()

    // ------------------------------------------------------------------ API

    fun feed(text: String) {
        for (ch in text) processCodePoint(ch.code)
    }

    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        var i = off
        val end = off + len
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            i++
            if (utfNeed > 0) {
                if (b and 0xC0 == 0x80) {
                    utfAccum = (utfAccum shl 6) or (b and 0x3F)
                    utfNeed--
                    if (utfNeed == 0) {
                        processCodePoint(utfAccum)
                        utfAccum = 0
                    }
                } else {
                    // invalid continuation; restart interpretation at this byte
                    processCodePoint(0xFFFD)
                    utfAccum = 0
                    utfNeed = 0
                    i-- // reprocess this byte
                }
            } else if (b < 0x80) {
                processCodePoint(b)
            } else if (b and 0xE0 == 0xC0) {
                utfAccum = b and 0x1F
                utfNeed = 1
            } else if (b and 0xF0 == 0xE0) {
                utfAccum = b and 0x0F
                utfNeed = 2
            } else if (b and 0xF8 == 0xF0) {
                utfAccum = b and 0x07
                utfNeed = 3
            } else {
                processCodePoint(0xFFFD)
            }
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val newChars = CharArray(newCols * newRows) { ' ' }
        val newStyles = IntArray(newCols * newRows)
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRows) {
            System.arraycopy(chars, r * cols, newChars, r * newCols, copyCols)
            System.arraycopy(styles, r * cols, newStyles, r * newCols, copyCols)
        }
        chars = newChars
        styles = newStyles
        cols = newCols
        rows = newRows
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        savedCol = savedCol.coerceIn(0, cols - 1)
        savedRow = savedRow.coerceIn(0, rows - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
        // alt-screen snapshot dimensions no longer match; drop stale snapshot
        mainChars = null
        mainStyles = null
    }

    fun getCharAt(row: Int, col: Int): Char = chars[row * cols + col]
    fun getStyleAt(row: Int, col: Int): Int = styles[row * cols + col]

    fun getRowText(row: Int): String {
        val sb = StringBuilder(cols)
        for (c in 0 until cols) {
            val ch = chars[row * cols + c]
            if (styleFlags(styles[row * cols + c]) and FLAG_WIDE_CONT == 0) sb.append(ch)
        }
        return sb.toString().trimEnd()
    }

    fun getScreenText(): String = (0 until rows).joinToString("\n") { getRowText(it) }

    /** Exact 0xRRGGBB for a dynamic truecolor index, or null for palette indices. */
    fun rgbAtIndex(idx: Int): Int? =
        if (idx in 256 until 256 + dynamicCount) dynamicRgb[idx - 256] else null

    /**
     * Allocates (or reuses) a dynamic slot for an exact RGB color. Falls back
     * to nearest-palette quantization when all slots are in use.
     */
    private fun allocDynamicColor(r: Int, g: Int, b: Int): Int {
        val value = (r shl 16) or (g shl 8) or b
        for (i in 0 until dynamicCount) {
            if (dynamicRgb[i] == value) return 256 + i
        }
        if (dynamicCount < DYNAMIC_MAX) {
            dynamicRgb[dynamicCount] = value
            return 256 + dynamicCount++
        }
        return nearestPaletteIndex(r, g, b)
    }

    // ---------------------------------------------------------- scrollback

    fun clearScrollback() {
        sbChars.clear()
        sbStyles.clear()
    }

    fun scrollbackCharAt(line: Int, col: Int): Char {
        val row = sbChars[line]
        return if (col < row.size) row[col] else ' '
    }

    fun scrollbackStyleAt(line: Int, col: Int): Int {
        val row = sbStyles[line]
        return if (col < row.size) row[col] else 0
    }

    fun getScrollbackRowText(line: Int): String {
        val row = sbChars[line]
        val styleRow = sbStyles[line]
        val sb = StringBuilder(row.size)
        for (c in 0 until row.size) {
            if (styleFlags(styleRow[c]) and FLAG_WIDE_CONT == 0) sb.append(row[c])
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------- internal

    /** Cell style = fg(9b) | bg(9b<<9) | flags(7b<<18). */
    private fun cellStyle(extraFlags: Int = 0): Int =
        curFg or (curBg shl 9) or ((curFlags or extraFlags) shl 18)

    private fun putChar(ch: Char) {
        if (pendingWrap) {
            cursorCol = 0
            pendingWrap = false
            if (cursorRow == scrollBottom) {
                scrollUp(1)
            } else if (cursorRow < rows - 1) {
                cursorRow++
            }
        }
        val wide = isWide(ch.code)
        if (wide && cursorCol >= cols - 1) {
            // no room for wide char: wrap first
            cursorCol = 0
            if (cursorRow == scrollBottom) {
                scrollUp(1)
            } else if (cursorRow < rows - 1) {
                cursorRow++
            }
        }
        val idx = cursorRow * cols + cursorCol
        // clear wide-pair artifacts at target and neighbor cells
        if (cursorCol > 0 && styleFlags(styles[idx - 1]) and FLAG_WIDE != 0) {
            styles[idx - 1] = cellStyle() // de-wide the orphaned lead cell
            chars[idx - 1] = ' '
        }
        if (idx + 1 < chars.size && styleFlags(styles[idx + 1]) and FLAG_WIDE_CONT != 0) {
            styles[idx + 1] = cellStyle() // de-cont the orphaned continuation cell
        }
        chars[idx] = ch
        styles[idx] = cellStyle(if (wide) FLAG_WIDE else 0)
        if (wide && cursorCol + 1 < cols) {
            val cont = idx + 1
            chars[cont] = ' '
            styles[cont] = cellStyle(FLAG_WIDE_CONT)
            cursorCol += 2
        } else if (cursorCol + 1 < cols) {
            cursorCol++
        } else {
            pendingWrap = true
        }
    }

    private fun blankCells() {
        chars.fill(' ')
        styles.fill(0)
    }

    private fun clearRegion(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        for (r in startRow..endRow) {
            if (r >= rows) break
            val cStart = if (r == startRow) startCol else 0
            val cEnd = if (r == endRow) endCol else cols - 1
            for (c in cStart..cEnd) {
                if (c > cols - 1) break
                chars[r * cols + c] = ' '
                styles[r * cols + c] = 0
            }
        }
    }

    private fun scrollUp(n: Int, top: Int = scrollTop, bottom: Int = scrollBottom) {
        if (bottom < top) return
        val range = bottom - top + 1
        val k = n.coerceIn(0, range)
        if (k > 0 && top == 0 && !altScreen) {
            for (r in 0 until k) {
                sbChars.addLast(chars.copyOfRange(r * cols, r * cols + cols))
                sbStyles.addLast(styles.copyOfRange(r * cols, r * cols + cols))
                if (sbChars.size > MAX_SCROLLBACK) {
                    sbChars.removeFirst()
                    sbStyles.removeFirst()
                }
            }
        }
        for (r in top until bottom + 1 - k) {
            val src = (r + k) * cols
            val dst = r * cols
            System.arraycopy(chars, src, chars, dst, cols)
            System.arraycopy(styles, src, styles, dst, cols)
        }
        clearRegion(bottom + 1 - k, 0, bottom, cols - 1)
    }

    private fun scrollDown(n: Int, top: Int = scrollTop, bottom: Int = scrollBottom) {
        if (bottom < top) return
        val range = bottom - top + 1
        val k = n.coerceIn(0, range)
        for (r in bottom downTo top + k) {
            val src = (r - k) * cols
            val dst = r * cols
            System.arraycopy(chars, src, chars, dst, cols)
            System.arraycopy(styles, src, styles, dst, cols)
        }
        clearRegion(top, 0, top + k - 1, cols - 1)
    }

    private fun saveCursor() {
        savedCol = cursorCol
        savedRow = cursorRow
    }

    private fun restoreCursor() {
        cursorCol = savedCol.coerceIn(0, cols - 1)
        cursorRow = savedRow.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    private fun enterAltScreen(clear: Boolean, saveCursor: Boolean) {
        if (altScreen) {
            if (clear) clearRegion(0, 0, rows - 1, cols - 1)
            return
        }
        if (saveCursor) {
            mainCol = cursorCol
            mainRow = cursorRow
        } else {
            mainCol = cursorCol
            mainRow = cursorRow
        }
        mainChars = chars.copyOf()
        mainStyles = styles.copyOf()
        altScreen = true
        if (clear) blankCells()
        cursorCol = 0
        cursorRow = 0
        pendingWrap = false
    }

    private fun exitAltScreen() {
        if (!altScreen) return
        mainChars?.let { chars = it.copyOf() }
        mainStyles?.let { styles = it.copyOf() }
        mainChars = null
        mainStyles = null
        altScreen = false
        cursorCol = mainCol.coerceIn(0, cols - 1)
        cursorRow = mainRow.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    private fun fullReset() {
        state = STATE_GROUND
        utfAccum = 0
        utfNeed = 0
        csiParams.setLength(0)
        curFg = FG_DEFAULT
        curBg = BG_DEFAULT
        curFlags = 0
        cursorCol = 0
        cursorRow = 0
        savedCol = 0
        savedRow = 0
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
        cursorVisible = true
        altScreen = false
        mainChars = null
        mainStyles = null
        clearScrollback()
        blankCells()
    }

    // -------------------------------------------------------------- parsing

    private fun processCodePoint(cp: Int) {
        when (state) {
            STATE_GROUND -> processGround(cp)
            STATE_ESC -> processEsc(cp)
            STATE_CSI -> processCsi(cp)
            STATE_OSC -> processOsc(cp)
            STATE_OSC_ESC -> {
                if (cp == '\\'.code) {
                    state = STATE_GROUND
                    onOscComplete()
                } else if (cp == 0x1B) {
                    onOscComplete()
                    processEsc(cp)
                } else {
                    // ESC + other: terminate the OSC and treat this byte as
                    // the start of a new escape sequence (e.g. ESC [ ... )
                    onOscComplete()
                    state = STATE_ESC
                    processEsc(cp)
                }
            }
            STATE_DCS, STATE_DCS_ESC -> {
                state = if (cp == 0x1B) STATE_DCS_ESC else STATE_DCS
                if (state == STATE_DCS_ESC && cp == '\\'.code) state = STATE_GROUND
                // DCS/other sequences consumed and ignored
            }
            STATE_CHARSET -> state = STATE_GROUND
        }
    }

    private fun processGround(cp: Int) {
        when {
            cp == 0x1B -> state = STATE_ESC
            cp == 0x07 -> bellListener?.invoke()
            cp == 0x08 -> { // BS
                if (cursorCol > 0) cursorCol--
                pendingWrap = false
            }
            cp == 0x09 -> { // HT
                val next = ((cursorCol / 8) + 1) * 8
                cursorCol = if (next < cols) next else cols - 1
                pendingWrap = false
            }
            cp == 0x0A || cp == 0x0B || cp == 0x0C -> { // LF VT FF
                pendingWrap = false
                if (cursorRow == scrollBottom) {
                    scrollUp(1)
                } else if (cursorRow < rows - 1) {
                    cursorRow++
                }
                // below the scroll region: plain move down, no region scrolling
            }
            cp == 0x0D -> { // CR
                cursorCol = 0
                pendingWrap = false
            }
            cp < 0x20 || cp == 0x7F -> { /* other C0 ignored */ }
            else -> putChar(cp.toChar())
        }
    }

    private fun processEsc(cp: Int) {
        when (cp.toChar()) {
            '[' -> {
                state = STATE_CSI
                csiParams.setLength(0)
                csiPrivate = false
            }
            ']' -> {
                state = STATE_OSC
                oscBuffer.setLength(0)
            }
            'P', 'X', '^', '_' -> state = STATE_DCS
            '(' , ')', '*', '+' -> state = STATE_CHARSET
            '7' -> { saveCursor(); state = STATE_GROUND }
            '8' -> { restoreCursor(); state = STATE_GROUND }
            'D' -> { // IND
                if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
                state = STATE_GROUND
            }
            'M' -> { // RI
                if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
                state = STATE_GROUND
            }
            'E' -> { // NEL
                cursorCol = 0
                if (cursorRow == scrollBottom) scrollUp(1) else cursorRow++
                state = STATE_GROUND
            }
            'c' -> { fullReset(); state = STATE_GROUND }
            '=', '>' -> state = STATE_GROUND // keypad modes ignored
            else -> state = STATE_GROUND
        }
    }

    private fun processOsc(cp: Int) {
        when {
            cp == 0x07 -> {
                onOscComplete()
                state = STATE_GROUND
            }
            cp == 0x1B -> state = STATE_OSC_ESC
            cp >= 0x20 -> oscBuffer.append(cp.toChar())
            else -> state = STATE_GROUND
        }
    }

    private fun onOscComplete() {
        val text = oscBuffer.toString()
        val semi = text.indexOf(';')
        if (semi > 0) {
            val num = text.substring(0, semi).toIntOrNull()
            val value = text.substring(semi + 1)
            if (num == 0 || num == 2) titleListener?.invoke(value)
        }
        oscBuffer.setLength(0)
    }

    private fun processCsi(cp: Int) {
        val c = cp.toChar()
        when {
            cp in 0x30..0x3F -> { // digits ; : < = > ?
                if (c == '?' || c == '<' || c == '=' || c == '>' ) csiPrivate = true
                if (csiParams.length < 24) csiParams.append(c)
            }
            cp in 0x20..0x2F -> { // intermediate bytes: collect, ignore meaning
                if (csiParams.length < 24) csiParams.append(c)
            }
            cp in 0x40..0x7E -> {
                dispatchCsi(c)
                state = STATE_GROUND
            }
            else -> state = STATE_GROUND // unexpected byte aborts sequence
        }
    }

    private fun paramsList(default: Int = 1): IntArray {
        val s = csiParams.toString().trimStart('?', '<', '=', '>')
        if (s.isEmpty()) return intArrayOf(default)
        val parts = s.split(';')
        return IntArray(parts.size) { i -> parts[i].toIntOrNull() ?: default }
    }

    private fun param(i: Int, default: Int = 1): Int {
        val list = paramsList(default)
        return list.getOrElse(i) { default }
    }

    private fun dispatchCsi(c: Char) {
        if (csiPrivate) {
            when (c) {
                'h' -> {
                    for (p in paramsList()) when (p) {
                        25 -> cursorVisible = true
                        1049 -> enterAltScreen(clear = true, saveCursor = true)
                        1047, 47 -> enterAltScreen(clear = false, saveCursor = false)
                        1048 -> saveCursor()
                    }
                }
                'l' -> {
                    for (p in paramsList()) when (p) {
                        25 -> cursorVisible = false
                        1049 -> { exitAltScreen(); restoreCursor() }
                        1047 -> { clearRegion(0, 0, rows - 1, cols - 1); exitAltScreen() }
                        47 -> exitAltScreen()
                        1048 -> restoreCursor()
                    }
                }
            }
            return
        }
        when (c) {
            'A' -> {
                val top = if (cursorRow >= scrollTop) scrollTop else 0
                cursorRow = (cursorRow - param(0)).coerceAtLeast(top)
                pendingWrap = false
            }
            'B', 'e' -> {
                val bottom = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
                cursorRow = (cursorRow + param(0)).coerceAtMost(bottom)
                pendingWrap = false
            }
            'C', 'a' -> { cursorCol = (cursorCol + param(0)).coerceAtMost(cols - 1); pendingWrap = false }
            'D' -> { cursorCol = (cursorCol - param(0)).coerceAtLeast(0); pendingWrap = false }
            'E' -> { cursorRow = (cursorRow + param(0)).coerceAtMost(scrollBottom).coerceAtMost(rows - 1); cursorCol = 0; pendingWrap = false }
            'F' -> { cursorRow = (cursorRow - param(0)).coerceAtLeast(scrollTop).coerceAtLeast(0); cursorCol = 0; pendingWrap = false }
            'G', '`' -> { cursorCol = (param(0) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'd' -> { cursorRow = (param(0) - 1).coerceIn(0, rows - 1); pendingWrap = false }
            'H', 'f' -> {
                cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (param(1, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> when (param(0, 0)) {
                0 -> clearRegion(cursorRow, cursorCol, rows - 1, cols - 1)
                1 -> clearRegion(0, 0, cursorRow, cursorCol)
                2, 3 -> clearRegion(0, 0, rows - 1, cols - 1)
            }
            'K' -> when (param(0, 0)) {
                0 -> clearRegion(cursorRow, cursorCol, cursorRow, cols - 1)
                1 -> clearRegion(cursorRow, 0, cursorRow, cursorCol)
                2 -> clearRegion(cursorRow, 0, cursorRow, cols - 1)
            }
            'L' -> if (cursorRow in scrollTop..scrollBottom) {
                scrollDown(param(0), top = cursorRow, bottom = scrollBottom)
            }
            'M' -> if (cursorRow in scrollTop..scrollBottom) {
                scrollUp(param(0), top = cursorRow, bottom = scrollBottom)
            }
            'P' -> {
                val n = param(0).coerceIn(0, cols - cursorCol)
                val idx = cursorRow * cols + cursorCol
                System.arraycopy(chars, idx + n, chars, idx, cols - cursorCol - n)
                System.arraycopy(styles, idx + n, styles, idx, cols - cursorCol - n)
                for (i in cols - n until cols) {
                    chars[cursorRow * cols + i] = ' '
                    styles[cursorRow * cols + i] = 0
                }
                pendingWrap = false
            }
            '@' -> {
                val n = param(0).coerceIn(0, cols - cursorCol)
                val idx = cursorRow * cols + cursorCol
                System.arraycopy(chars, idx, chars, idx + n, cols - cursorCol - n)
                System.arraycopy(styles, idx, styles, idx + n, cols - cursorCol - n)
                for (i in idx until idx + n) {
                    chars[i] = ' '
                    styles[i] = 0
                }
                pendingWrap = false
            }
            'X' -> {
                val n = param(0).coerceIn(0, cols - cursorCol)
                clearRegion(cursorRow, cursorCol, cursorRow, cursorCol + n - 1)
                pendingWrap = false
            }
            'S' -> { scrollUp(param(0)); }
            'T' -> { scrollDown(param(0)); }
            'r' -> {
                val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                } else {
                    scrollTop = 0
                    scrollBottom = rows - 1
                }
                cursorCol = 0
                cursorRow = scrollTop
                pendingWrap = false
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'm' -> sgr()
        }
    }

    private fun sgr() {
        if (csiParams.isEmpty()) {
            curFg = FG_DEFAULT
            curBg = BG_DEFAULT
            curFlags = 0
            return
        }
        val p = csiParams.toString().split(';')
        var i = 0
        while (i < p.size) {
            val n = p[i].toIntOrNull() ?: 0
            when (n) {
                0 -> { curFg = FG_DEFAULT; curBg = BG_DEFAULT; curFlags = 0 }
                1 -> curFlags = curFlags or FLAG_BOLD
                2 -> curFlags = curFlags or FLAG_DIM
                4 -> curFlags = curFlags or FLAG_UNDERLINE
                7 -> curFlags = curFlags or FLAG_REVERSE
                8 -> curFlags = curFlags or FLAG_INVISIBLE
                21, 22 -> curFlags = curFlags and (FLAG_BOLD or FLAG_DIM).inv()
                24 -> curFlags = curFlags and FLAG_UNDERLINE.inv()
                27 -> curFlags = curFlags and FLAG_REVERSE.inv()
                28 -> curFlags = curFlags and FLAG_INVISIBLE.inv()
                in 30..37 -> curFg = n - 30
                39 -> curFg = FG_DEFAULT
                in 40..47 -> curBg = n - 40
                49 -> curBg = BG_DEFAULT
                in 90..97 -> curFg = n - 90 + 8
                in 100..107 -> curBg = n - 100 + 8
                38, 48 -> {
                    val target = n
                    var color = FG_DEFAULT
                    val mode = p.getOrNull(i + 1)?.toIntOrNull()
                    when (mode) {
                        5 -> {
                            val idx = p.getOrNull(i + 2)?.toIntOrNull() ?: -1
                            if (idx in 0..255) color = idx
                            i += 2
                        }
                        2 -> {
                            val r = p.getOrNull(i + 2)?.toIntOrNull() ?: -1
                            val g = p.getOrNull(i + 3)?.toIntOrNull() ?: -1
                            val b = p.getOrNull(i + 4)?.toIntOrNull() ?: -1
                            if (r in 0..255 && g in 0..255 && b in 0..255) {
                                color = allocDynamicColor(r, g, b)
                            }
                            i += 4
                        }
                    }
                    if (target == 38) curFg = color else curBg = color
                }
            }
            i++
        }
    }
}
