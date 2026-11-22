package com.sandboxforge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0F1115)
val SurfaceDark = Color(0xFF171A21)
val SurfaceElevated = Color(0xFF1F2430)
val BorderDark = Color(0xFF282E3D)
val AccentBlue = Color(0xFF3B82F6)
val AccentCyan = Color(0xFF22D3EE)
val StatusGreen = Color(0xFF22C55E)
val StatusAmber = Color(0xFFF59E0B)
val StatusRed = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF3F4F6)
val TextMuted = Color(0xFF9CA3AF)

private val SandboxColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentCyan,
    onSecondary = Color(0xFF041317),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = BorderDark,
    error = StatusRed,
    onError = Color.White
)

@Composable
fun SandboxForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SandboxColorScheme,
        content = content
    )
}
