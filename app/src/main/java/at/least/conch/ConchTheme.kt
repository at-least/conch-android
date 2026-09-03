package at.least.conch

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * The app's single Material 3 theme.
 *
 * Deliberately a fixed palette, not Android's per-device Material You colors
 * ([dynamicLightColorScheme] / [dynamicDarkColorScheme]): the brief is an
 * Apple-HIG-flavoured look — one considered palette that reads the same on
 * every device, the way iOS's own apps do, rather than one that recolors
 * itself to the user's wallpaper. Shape and type scale follow the same
 * source (iOS grouped lists, SF-style large titles).
 *
 * The terminal surface is deliberately NOT themed from here — its colors
 * come from the user's [TerminalTheme] and must stay stable in both modes.
 */
@Composable
fun ConchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme

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
        MaterialTheme(
            colorScheme = scheme,
            shapes = ConchShapes,
            typography = ConchTypography,
            content = content,
        )
    }
}

/**
 * Semantic colors Material 3 has no slot for. A connected session is
 * "success", a retrying one is "warning" — neither is `primary` and
 * neither may be a raw hex sprinkled through the screens (the old code
 * hardcoded `Color(0xFF23D18B)` in five files). Fixed across both themes,
 * exactly like `error`: a green that shifts with the wallpaper stops
 * meaning "connected" — matched to iOS's own systemGreen/systemOrange so it
 * reads as a native status color rather than a Material tertiary color.
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
    success = Color(0xFF34C759), // iOS systemGreen
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDCF6E3),
    onSuccessContainer = Color(0xFF0B3D17),
    warning = Color(0xFFFF9500), // iOS systemOrange
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFE8CC),
    onWarningContainer = Color(0xFF4A2900),
)

private val DarkExtras = ConchColors(
    success = Color(0xFF30D158), // iOS systemGreen (dark)
    onSuccess = Color(0xFF00390D),
    successContainer = Color(0xFF0F4B22),
    onSuccessContainer = Color(0xFFA9F3BB),
    warning = Color(0xFFFF9F0A), // iOS systemOrange (dark)
    onWarning = Color(0xFF462600),
    warningContainer = Color(0xFF603C00),
    onWarningContainer = Color(0xFFFFDDAA),
)

/**
 * Apple system-blue accent on an iOS "grouped table view" surface stack:
 * a soft gray canvas ([background]) with pure-white inset cards ([surface])
 * on top — the look behind Settings, Files, Mail. `surfaceContainer*` is a
 * near-flat ramp between those two rather than Material's usual tonal
 * blend, so grouping reads as "gray canvas, white card", not as a tint.
 */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF), // iOS systemBlue
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF00376B),
    secondary = Color(0xFF6C6C70), // iOS secondaryLabel
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E9EB),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF34C759),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCF6E3),
    onTertiaryContainer = Color(0xFF0B3D17),
    error = Color(0xFFFF3B30), // iOS systemRed
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
    background = Color(0xFFF2F2F7), // iOS systemGroupedBackground
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF), // iOS secondarySystemGroupedBackground
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFEBEBF0),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFFC6C6C8), // iOS separator
    outlineVariant = Color(0xFFE5E5EA),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFF0A84FF),
    scrim = Color(0xFF000000),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF), // iOS systemBlue (dark)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF00376B),
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = Color(0xFF98989D), // iOS secondaryLabel (dark)
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFF30D158),
    onTertiary = Color(0xFF00390D),
    tertiaryContainer = Color(0xFF0F4B22),
    onTertiaryContainer = Color(0xFFA9F3BB),
    error = Color(0xFFFF453A), // iOS systemRed (dark)
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF690003),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF000000), // iOS systemGroupedBackground (dark)
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E), // iOS secondarySystemGroupedBackground (dark)
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF98989D),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = Color(0xFF38383A), // iOS separator (dark)
    outlineVariant = Color(0xFF2C2C2E),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFF007AFF),
    scrim = Color(0xFF000000),
)

/**
 * iOS corner radii (continuous corners, approximated with plain rounded
 * rects — Compose has no native squircle): ~8pt controls, ~10pt fields,
 * ~14pt grouped-list cards, ~20pt sheets/dialogs.
 */
private val ConchShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * SF-style type scale mapped onto Material's roles: a bold 34pt large
 * title, 17pt body (both match iOS's own sizes), and slightly tighter
 * tracking throughout than Material's default — Apple's system font sits
 * tighter than Roboto's default spacing.
 */
private val ConchTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            lineHeight = 41.sp,
            letterSpacing = 0.3.sp,
        ),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.copy(letterSpacing = 0.1.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}
