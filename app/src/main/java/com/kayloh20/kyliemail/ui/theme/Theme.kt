package com.kayloh20.kyliemail.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kayloh20.kyliemail.R

/** The Kylie Mail palette. Names match the design tokens. */
object Palette {
    val BgPrimary = Color(0xFF0F0F12)
    val BgSecondary = Color(0xFF16161D)
    val BgTertiary = Color(0xFF1E1E28)
    val BgElevated = Color(0xFF252530)

    val TextPrimary = Color(0xFFE8E6F0)
    val TextSecondary = Color(0xFF9895A8)
    val TextMuted = Color(0xFF5A5670)

    val Accent = Color(0xFF9271DB)
    val AccentHover = Color(0xFFA88BE6)
    val AccentSubtle = Color(0x1F9271DB) // rgba(146,113,219,0.12)
    val AccentGlow = Color(0x409271DB) // rgba(146,113,219,0.25)

    val Border = Color(0x0FFFFFFF) // rgba(255,255,255,0.06)
    val BorderStrong = Color(0x1AFFFFFF) // rgba(255,255,255,0.10)

    val Error = Color(0xFFD94455)
    val Warning = Color(0xFFE0A040)
    val Success = Color(0xFF4ADE80)
}

/** Palette colors with no Material 3 role. Read them with [KylieTheme.colors]. */
@Immutable
data class ExtendedColors(
    val textMuted: Color,
    val accentHover: Color,
    val accentGlow: Color,
    val borderStrong: Color,
    val warning: Color,
    val success: Color,
)

private val Extended = ExtendedColors(
    textMuted = Palette.TextMuted,
    accentHover = Palette.AccentHover,
    accentGlow = Palette.AccentGlow,
    borderStrong = Palette.BorderStrong,
    warning = Palette.Warning,
    success = Palette.Success,
)

private val LocalExtendedColors = staticCompositionLocalOf { Extended }

object KylieTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

private val Colors = darkColorScheme(
    // Accent
    primary = Palette.Accent,
    // Dark text on the accent keeps button labels above 4.5:1 contrast; white would be about 3.8:1.
    onPrimary = Palette.BgPrimary,
    primaryContainer = Palette.AccentSubtle,
    onPrimaryContainer = Palette.AccentHover,
    inversePrimary = Palette.AccentHover,
    secondary = Palette.AccentHover,
    onSecondary = Palette.BgPrimary,
    // Selected rows and folders.
    secondaryContainer = Palette.AccentSubtle,
    onSecondaryContainer = Palette.TextPrimary,
    tertiary = Palette.AccentHover,
    onTertiary = Palette.BgPrimary,
    tertiaryContainer = Palette.AccentSubtle,
    onTertiaryContainer = Palette.AccentHover,

    // Surfaces, from the window up to menus and dialogs.
    background = Palette.BgPrimary,
    onBackground = Palette.TextPrimary,
    surface = Palette.BgPrimary,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.BgTertiary,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceDim = Palette.BgPrimary,
    surfaceBright = Palette.BgElevated,
    surfaceContainerLowest = Palette.BgPrimary,
    surfaceContainerLow = Palette.BgSecondary,
    surfaceContainer = Palette.BgElevated,
    surfaceContainerHigh = Palette.BgElevated,
    surfaceContainerHighest = Palette.BgElevated,
    // No purple tint on raised surfaces, so they stay exactly the palette colors.
    surfaceTint = Color.Transparent,
    // Snackbars and tooltips.
    inverseSurface = Palette.BgElevated,
    inverseOnSurface = Palette.TextPrimary,

    outline = Palette.BorderStrong,
    outlineVariant = Palette.Border,

    error = Palette.Error,
    onError = Color.White,
    errorContainer = Color(0x29D94455),
    onErrorContainer = Palette.TextPrimary,
    scrim = Color.Black,
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/** Inter throughout, with 14px as the base size for body text. */
private val Type = Typography(
    displayLarge = style(48, 56, FontWeight.SemiBold, -0.5),
    displayMedium = style(40, 48, FontWeight.SemiBold, -0.5),
    displaySmall = style(32, 40, FontWeight.SemiBold, -0.25),
    headlineLarge = style(28, 36, FontWeight.SemiBold, -0.25),
    headlineMedium = style(24, 32, FontWeight.SemiBold),
    headlineSmall = style(20, 28, FontWeight.SemiBold),
    titleLarge = style(18, 26, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.Medium),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(14, 22),
    bodyMedium = style(14, 20),
    bodySmall = style(12, 16),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KylieMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type) {
        CompositionLocalProvider(
            LocalExtendedColors provides Extended,
            // Hover and press states take the accent-hover color.
            LocalRippleConfiguration provides RippleConfiguration(color = Palette.AccentHover),
            // Selected text glows in the accent.
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = Palette.Accent,
                backgroundColor = Palette.AccentGlow,
            ),
            content = content,
        )
    }
}
