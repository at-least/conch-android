package at.least.conch

/**
 * Terminal color theme: background, default foreground and the 16 ANSI
 * base colors (indices 0-15 of the 256-color palette). Colors are 0xRRGGBB;
 * alpha is applied by the renderer. Pure Kotlin — host-testable.
 */
data class TerminalTheme(
    val name: String,
    val bg: Int,
    val defaultFg: Int,
    val base16: IntArray,
) {
    /** Writes the base colors into [target][0..15] (never aliases shared state). */
    fun base16Into(target: IntArray) {
        for (i in 0 until 16) target[i] = base16[i]
    }

    override fun equals(other: Any?): Boolean =
        other is TerminalTheme && other.name == name && other.bg == bg &&
            other.defaultFg == defaultFg && other.base16.contentEquals(base16)

    override fun hashCode(): Int = name.hashCode() * 31 + base16.contentHashCode()

    companion object {
        /** WCAG relative luminance of a 0xRRGGBB color. */
        fun luminance(rgb: Int): Double {
            fun channel(v: Int): Double {
                val c = (v and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(rgb shr 16) + 0.7152 * channel(rgb shr 8) + 0.0722 * channel(rgb)
        }

        val DEFAULT = TerminalTheme(
            name = "Default",
            bg = 0x1A1B26,
            defaultFg = 0xE0E0E0,
            base16 = intArrayOf(
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510,
                0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543,
                0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
            )
        )

        val DRACULA = TerminalTheme(
            name = "Dracula",
            bg = 0x282A36,
            defaultFg = 0xF8F8F2,
            base16 = intArrayOf(
                0x000000, 0xFF5555, 0x50FA7B, 0xF1FA8C,
                0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xBFBFBF,
                0x4D4D4D, 0xFF6E67, 0x5AF78E, 0xF4F99D,
                0xCAA9FA, 0xFF92D0, 0x9AEDFE, 0xE6E6E6,
            )
        )

        val SOLARIZED_DARK = TerminalTheme(
            name = "Solarized Dark",
            bg = 0x002B36,
            defaultFg = 0x839496,
            base16 = intArrayOf(
                0x002B36, 0xDC322F, 0x859900, 0xB58900,
                0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83,
                0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
            )
        )

        val NORD = TerminalTheme(
            name = "Nord",
            bg = 0x2E3440,
            defaultFg = 0xD8DEE9,
            base16 = intArrayOf(
                0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B,
                0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
                0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B,
                0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
            )
        )

        val GRUVBOX_DARK = TerminalTheme(
            name = "Gruvbox Dark",
            bg = 0x282828,
            defaultFg = 0xEBDBB2,
            base16 = intArrayOf(
                0x282828, 0xCC241D, 0x98971A, 0xD79921,
                0x458588, 0xB16286, 0x689D6A, 0xA89984,
                0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F,
                0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
            )
        )

        val ALL: List<TerminalTheme> = listOf(DEFAULT, DRACULA, SOLARIZED_DARK, NORD, GRUVBOX_DARK)

        /** Looks a preset up by name; unknown names fall back to [DEFAULT]. */
        fun byName(name: String?): TerminalTheme =
            ALL.firstOrNull { it.name == name } ?: DEFAULT
    }
}
