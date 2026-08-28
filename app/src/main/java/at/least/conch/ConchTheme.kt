package at.least.conch

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app's single Material 3 theme.
 *
 * Before this existed every `setContent { … }` rendered against Compose's
 * *default* MaterialTheme — the baseline purple light scheme — so the app
 * ignored the system dark-mode setting entirely and no screen shared a
 * palette with any other. Everything now goes through here.
 *
 * Native first: on Android 12+ the scheme is the device's own Material You
 * palette ([dynamicLightColorScheme] / [dynamicDarkColorScheme]); older
 * devices fall back to the Conch blue/green brand scheme below.
 *
 * The terminal surface is deliberately NOT themed from here — its colors
 * come from the user's [TerminalTheme] and must stay stable in both modes.
 */
@Composable
fun ConchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    // Edge-to-edge is mandatory from targetSdk 35: the only thing left to
    // set is whether the system-bar glyphs are drawn dark-on-light.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalConchColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Semantic colors Material 3 has no slot for. A connected session is
 * "success", a retrying one is "warning" — neither is `primary` and
 * neither may be a raw hex sprinkled through the screens (the old code
 * hardcoded `Color(0xFF23D18B)` in five files). Kept fixed across dynamic
 * color, exactly like `error`: a green that shifts with the wallpaper
 * stops meaning "connected".
 */
data class ConchColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

// A CompositionLocal is how Material 3 itself ships its color slots; adding
// one is the supported way to extend the palette with app-level semantics.
@Suppress("ComposeCompositionLocalUsage")
val LocalConchColors = staticCompositionLocalOf { LightExtras }

/** `MaterialTheme.conch.success` — reads like the built-in color slots. */
val MaterialTheme.conch: ConchColors
    @Composable get() = LocalConchColors.current

private val LightExtras = ConchColors(
    success = Color(0xFF146C43),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFC4F0D8),
    onSuccessContainer = Color(0xFF002111),
    warning = Color(0xFF855400),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB0),
    onWarningContainer = Color(0xFF2A1800),
)

private val DarkExtras = ConchColors(
    success = Color(0xFF6BDBA6),
    onSuccess = Color(0xFF003825),
    successContainer = Color(0xFF005138),
    onSuccessContainer = Color(0xFF88F8C0),
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF452B00),
    warningContainer = Color(0xFF633F00),
    onWarningContainer = Color(0xFFFFDDB0),
)

/** Brand fallback for pre-Android-12 devices: Conch blue, terminal green as tertiary. */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B61A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF006C4C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF88F8C0),
    onTertiaryContainer = Color(0xFF002114),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFECEEF3),
    surfaceContainerHighest = Color(0xFFE6E8EE),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFA0C9FF),
    scrim = Color(0xFF000000),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA0C9FF),
    onPrimary = Color(0xFF003259),
    primaryContainer = Color(0xFF00497F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253141),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFF6BDBA6),
    onTertiary = Color(0xFF003825),
    tertiaryContainer = Color(0xFF005138),
    onTertiaryContainer = Color(0xFF88F8C0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF0B61A4),
    scrim = Color(0xFF000000),
)
