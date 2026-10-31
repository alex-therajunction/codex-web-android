package io.github.alextherajunction.codexweb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val CodexLime = Color(0xFFB9F47A)
val CodexLimeSoft = Color(0xFF334527)
val CodexInk = Color(0xFF10120E)
val CodexSurface = Color(0xFF181B16)
val CodexSurfaceHigh = Color(0xFF22261E)
val CodexOutline = Color(0xFF394031)
val CodexText = Color(0xFFF0F3EA)
val CodexMuted = Color(0xFFA5AD9B)
val CodexDanger = Color(0xFFFF8F82)
val CodexAmber = Color(0xFFF2C66D)

private val colors = darkColorScheme(
    primary = CodexLime,
    onPrimary = CodexInk,
    primaryContainer = CodexLimeSoft,
    onPrimaryContainer = CodexText,
    secondary = Color(0xFFB8CDB0),
    onSecondary = CodexInk,
    background = CodexInk,
    onBackground = CodexText,
    surface = CodexSurface,
    onSurface = CodexText,
    surfaceVariant = CodexSurfaceHigh,
    onSurfaceVariant = CodexMuted,
    outline = CodexOutline,
    error = CodexDanger,
    onError = CodexInk,
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 39.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun CodexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content,
    )
}
