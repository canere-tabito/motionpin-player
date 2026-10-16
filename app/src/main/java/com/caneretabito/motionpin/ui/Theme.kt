package com.caneretabito.motionpin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Obsidian = Color(0xFF0B0D12)
val Graphite = Color(0xFF1A1D25)
val Champagne = Color(0xFFC5A771)
val Ivory = Color(0xFFF2F3F6)
val Muted = Color(0xFFA9AEB8)

@Composable
fun MotionPinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Champagne, onPrimary = Obsidian, secondary = Champagne,
        background = Obsidian, surface = Graphite, onBackground = Ivory, onSurface = Ivory, onSurfaceVariant = Muted,
        surfaceVariant = Color(0xFF242832), outline = Color(0xFF454A55)),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp),
            headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 1.sp)), content = content)
}
