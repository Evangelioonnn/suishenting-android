package com.museum.reader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Paper = Color(0xFFF7F6F2)
val PaperDark = Color(0xFF111413)
val Ink = Color(0xFF191C1B)
val InkDark = Color(0xFFF0F2EF)
val Accent = Color(0xFF276B66)
val AccentDark = Color(0xFF89D5CB)
val AccentSoft = Color(0xFFDDECE8)
val Hairline = Color(0xFFE4E5E0)

internal val ColorScheme.prominentCardSubtitleColor: Color
    get() = onPrimary.copy(alpha = 0.82f)

internal val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Color(0xFF4B635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8E2),
    onSecondaryContainer = Color(0xFF112E2A),
    tertiary = Color(0xFF806026),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E4BD),
    onTertiaryContainer = Color(0xFF3E2C07),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F5F0),
    surfaceContainer = Color(0xFFEFEFE8),
    surfaceContainerHigh = Color(0xFFE9E9E2),
    surfaceContainerHighest = Color(0xFFE3E4DD),
    surfaceTint = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFF113D39),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFEFDF9),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEDE8),
    onSurfaceVariant = Color(0xFF5E6461),
    outline = Color(0xFF858B87),
    outlineVariant = Hairline,
    error = Color(0xFFB3261E),
)

internal val DarkColors = darkColorScheme(
    primary = AccentDark,
    secondary = Color(0xFFB6CCC5),
    onSecondary = Color(0xFF20352F),
    secondaryContainer = Color(0xFF364D45),
    onSecondaryContainer = Color(0xFFD2E8E0),
    tertiary = Color(0xFFDFC48C),
    onTertiary = Color(0xFF41310D),
    tertiaryContainer = Color(0xFF5A4720),
    onTertiaryContainer = Color(0xFFFCE6BA),
    surfaceContainerLowest = Color(0xFF0C100E),
    surfaceContainerLow = Color(0xFF191D1B),
    surfaceContainer = Color(0xFF1D2420),
    surfaceContainerHigh = Color(0xFF282F2B),
    surfaceContainerHighest = Color(0xFF333A35),
    surfaceTint = AccentDark,
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF174F4A),
    onPrimaryContainer = Color(0xFFA6F2E8),
    background = PaperDark,
    onBackground = InkDark,
    surface = Color(0xFF191D1B),
    onSurface = InkDark,
    surfaceVariant = Color(0xFF252A28),
    onSurfaceVariant = Color(0xFFC2C8C4),
    outline = Color(0xFF8C948F),
    outlineVariant = Color(0xFF343A37),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 39.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun MuseumReaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
