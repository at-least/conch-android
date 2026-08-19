package at.least.conch

/**
 * Bracketed paste mode helpers (xterm DECSET 2004), pure Kotlin.
 *
 * When the remote app enables 2004, pasted text is wrapped in `ESC[200~` /
 * `ESC[201~` markers so editors (vim, nano) treat it as a literal block
 * instead of executing embedded newlines. Without 2004 the paste is
 * newline-sanitized as best-effort fallback.
 */
object BracketedPaste {

    const val PASTE_START = "\u001b[200~"
    const val PASTE_END = "\u001b[201~"

    /** Wraps [text] in bracketed-paste markers. */
    fun wrap(text: String): String = PASTE_START + text + PASTE_END

    /**
     * Fallback normalization when the remote did NOT enable 2004:
     * CRLF and lone CR become LF; bare LF is kept as-is; other
     * characters pass through untouched.
     */
    fun sanitize(text: String): String {
        if (!text.contains('\r')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') i++ // CRLF -> single LF
                sb.append('\n')
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Heuristic: an IME `commitText` payload carrying line breaks is a
     * clipboard paste, not typing. Single-line commits stay on the raw
     * typing path (autocorrect, gesture input, composing).
     */
    fun looksLikePaste(text: String): Boolean =
        text.contains('\n') || text.contains('\r')
}
