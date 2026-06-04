package dev.fahim.livescanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-only "scanner console" palette.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5BC8FF),
    onPrimary = Color(0xFF00344B),
    primaryContainer = Color(0xFF004C6B),
    onPrimaryContainer = Color(0xFFCDE9FF),
    secondary = Color(0xFF8FD06A),
    onSecondary = Color(0xFF12380A),
    background = Color(0xFF0B1116),
    onBackground = Color(0xFFE2E7EB),
    surface = Color(0xFF121C24),
    onSurface = Color(0xFFE2E7EB),
    surfaceVariant = Color(0xFF1E2A33),
    onSurfaceVariant = Color(0xFFB3C0CA),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF3A4954),
)

@Composable
fun LiveScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
