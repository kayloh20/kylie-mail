package com.kayloh20.kyliemail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kayloh20.kyliemail.R

/** The design tokens for one appearance. Names match the CSS custom properties. */
@Immutable
data class Palette(
    val isDark: Boolean,
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val bgElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentHover: Color,
    val accentSubtle: Color,
    val accentGlow: Color,
    val border: Color,
    val borderStrong: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
) {
    companion object {
        val Dark = Palette(
            isDark = true,
            bgPrimary = Color(0xFF0F0F12),
            bgSecondary = Color(0xFF16161D),
            bgTertiary = Color(0xFF1E1E28),
            bgElevated = Color(0xFF252530),
            textPrimary = Color(0xFFE8E6F0),
            textSecondary = Color(0xFF9895A8),
            textMuted = Color(0xFF5A5670),
            accent = Color(0xFF9271DB),
            // Dark text on the accent is about 5:1; white would be about 3.8:1.
            onAccent = Color(0xFF0F0F12),
            accentHover = Color(0xFFA88BE6),
            accentSubtle = Color(0x1F9271DB), // 12%
            accentGlow = Color(0x409271DB), // 25%
            border = Color(0x0FFFFFFF), // white 6%
            borderStrong = Color(0x1AFFFFFF), // white 10%
            error = Color(0xFFD94455),
            warning = Color(0xFFE0A040),
            success = Color(0xFF4ADE80),
        )

        /**
         * The same identity on light backgrounds: cool lavender-tinted neutrals, and a deeper accent
         * so purple text and icons stay above 4.5:1 on white.
         */
        val Light = Palette(
            isDark = false,
            bgPrimary = Color(0xFFFCFBFE),
            bgSecondary = Color(0xFFF4F2F9),
            bgTertiary = Color(0xFFECE9F4),
            bgElevated = Color(0xFFFFFFFF),
            textPrimary = Color(0xFF1A1823),
            textSecondary = Color(0xFF615D73),
            textMuted = Color(0xFF9C98AE),
            accent = Color(0xFF7A55D0),
            onAccent = Color(0xFFFFFFFF),
            accentHover = Color(0xFF6A45C2),
            accentSubtle = Color(0x1A7A55D0), // 10%
            accentGlow = Color(0x337A55D0), // 20%
            border = Color(0x14140F28), // ink 8%
            borderStrong = Color(0x24140F28), // ink 14%
            error = Color(0xFFC93545),
            warning = Color(0xFFB8791A),
            success = Color(0xFF16A34A),
        )
    }
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

private fun Palette.extended() = ExtendedColors(textMuted, accentHover, accentGlow, borderStrong, warning, success)

private val LocalExtendedColors = staticCompositionLocalOf { Palette.Dark.extended() }

object KylieTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

private fun Palette.colorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSubtle,
        onPrimaryContainer = accentHover,
        inversePrimary = accentHover,
        secondary = accentHover,
        onSecondary = onAccent,
        // Selected rows and folders.
        secondaryContainer = accentSubtle,
        onSecondaryContainer = textPrimary,
        tertiary = accentHover,
        onTertiary = onAccent,
        tertiaryContainer = accentSubtle,
        onTertiaryContainer = accentHover,

        // Surfaces, from the window up to menus and dialogs.
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgPrimary,
        onSurface = textPrimary,
        surfaceVariant = bgTertiary,
        onSurfaceVariant = textSecondary,
        surfaceDim = bgPrimary,
        surfaceBright = bgElevated,
        surfaceContainerLowest = bgPrimary,
        surfaceContainerLow = bgSecondary,
        surfaceContainer = bgElevated,
        surfaceContainerHigh = bgElevated,
        surfaceContainerHighest = bgElevated,
        // No purple tint on raised surfaces, so they stay exactly the palette colors.
        surfaceTint = Color.Transparent,
        // Snackbars and tooltips.
        inverseSurface = if (isDark) bgElevated else textPrimary,
        inverseOnSurface = if (isDark) textPrimary else bgPrimary,

        outline = borderStrong,
        outlineVariant = border,

        error = error,
        onError = Color.White,
        errorContainer = error.copy(alpha = 0.16f),
        onErrorContainer = textPrimary,
        scrim = Color.Black,
    )
}

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

/** Tighter corners than Material's defaults: closer to a desktop app than to Gmail's pills. */
private val KylieShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Follows the system's light or dark setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KylieMailTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) Palette.Dark else Palette.Light
    MaterialTheme(colorScheme = palette.colorScheme(), typography = Type, shapes = KylieShapes) {
        CompositionLocalProvider(
            LocalExtendedColors provides palette.extended(),
            // Hover and press states take the accent-hover color.
            LocalRippleConfiguration provides RippleConfiguration(color = palette.accentHover),
            // Selected text glows in the accent.
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = palette.accent,
                backgroundColor = palette.accentGlow,
            ),
            content = content,
        )
    }
}
