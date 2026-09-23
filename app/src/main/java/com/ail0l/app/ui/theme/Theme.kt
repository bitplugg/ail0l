package com.ail0l.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF6C5CE7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E6FF),
    onPrimaryContainer = Color(0xFF1A0F54),
    secondary = Color(0xFF00CEC9),
    onSecondary = Color(0xFF00201F),
    secondaryContainer = Color(0xFFC3F4F1),
    onSecondaryContainer = Color(0xFF00201F),
    tertiary = Color(0xFFFD79A8),
    onTertiary = Color(0xFF4A0020),
    tertiaryContainer = Color(0xFFFFD9E4),
    onTertiaryContainer = Color(0xFF3E001B),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E6F2),
    onSurfaceVariant = Color(0xFF4A4954),
    outline = Color(0xFF7B7986)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6A9FF),
    onPrimary = Color(0xFF22116B),
    primaryContainer = Color(0xFF3F2F92),
    onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = Color(0xFF4DDDDB),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF005151),
    onSecondaryContainer = Color(0xFFC3F4F1),
    tertiary = Color(0xFFFFB0CE),
    onTertiary = Color(0xFF5E1133),
    tertiaryContainer = Color(0xFF7A284B),
    onTertiaryContainer = Color(0xFFFFD9E4),
    background = Color(0xFF141421),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF141421),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF4A4856),
    onSurfaceVariant = Color(0xFFCBC4D5),
    outline = Color(0xFF938F9E)
)

// expressive: крупные скругления, заметная типографика
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 57.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
)

@Composable
fun Ail0lTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic Color: подстраиваемся под обои системы (Android 12+)
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}