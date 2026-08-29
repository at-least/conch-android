package at.least.conch

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Terminal font families (iOS parity: `TerminalFont.swift`). The bundled
 * Nerd Font covers the glyph set TUIs actually draw (powerline/box-drawing
 * icons); we ship one known-good face instead of promising arbitrary
 * installs. [id] is the persisted setting value and matches iOS's raw
 * values so the choice reads the same on both platforms.
 */
enum class TerminalFont(val id: String, val displayName: String) {
    SYSTEM("system", "System monospace"),
    JETBRAINS_MONO_NERD("jetBrainsMonoNerd", "JetBrains Mono Nerd"),
    ;

    /** The face to draw with; the system monospace when the bundled font cannot load. */
    fun typeface(context: Context): Typeface = when (this) {
        SYSTEM -> Typeface.MONOSPACE
        JETBRAINS_MONO_NERD ->
            runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_nerd_regular) }.getOrNull()
                ?: Typeface.MONOSPACE
    }

    companion object {
        val ALL: List<TerminalFont> = listOf(SYSTEM, JETBRAINS_MONO_NERD)
        val DEFAULT: TerminalFont = SYSTEM

        /** Persisted id → font; unknown or absent reads as [DEFAULT]. */
        fun byId(id: String?): TerminalFont = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
