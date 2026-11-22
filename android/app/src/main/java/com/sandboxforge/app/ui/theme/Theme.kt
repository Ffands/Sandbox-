package com.sandboxforge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Crisper, higher-contrast dark engineering palette (Step 2 UI polish)
val BgDark = Color(0xFF0A0C11)
val SurfaceDark = Color(0xFF141822)
val SurfaceElevated = Color(0xFF1D2433)
val BorderDark = Color(0xFF334155)
val AccentBlue = Color(0xFF4F8BFF)
val AccentCyan = Color(0xFF22D3EE)
val StatusGreen = Color(0xFF34D399)
val StatusAmber = Color(0xFFFBBF24)
val StatusRed = Color(0xFFF87171)
val TextPrimary = Color(0xFFF8FAFC)
val TextMuted = Color(0xFFB6C2D6)

private val SandboxColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentCyan,
    onSecondary = Color(0xFF03151B),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = BorderDark,
    error = StatusRed,
    onError = Color(0xFF1F0707)
)

@Composable
fun SandboxForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SandboxColorScheme,
        content = content
    )
}
