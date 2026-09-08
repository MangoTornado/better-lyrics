package com.betterlyrics.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val scheme = darkColorScheme(
    primary = Color(0xFFE8DEF8),
    onPrimary = Color(0xFF1A1420),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFF2F0F5),
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Color(0xB3FFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F0F5),
    outline = Color(0x33FFFFFF),
)

private val typography = Typography(
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun BetterLyricsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
