package dev.fahim.livescanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

/** Design tokens for the current (day or night) treatment. */
val LocalFlightDeck = staticCompositionLocalOf { DayPalette }

/** Shorthand: `FlightDeck.cyan` inside any composable. */
val FlightDeck: FlightDeckPalette
    @Composable get() = LocalFlightDeck.current

@Composable
fun LiveScannerTheme(
    night: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (night) NightPalette else DayPalette

    // Material 3 still backs a few stock components (dialogs, text fields, sliders); map the
    // tokens onto its scheme so nothing falls back to purple.
    val scheme = darkColorScheme(
        primary = palette.cyan,
        onPrimary = palette.bg,
        primaryContainer = palette.cyanBg,
        onPrimaryContainer = palette.cyan,
        secondary = palette.green,
        onSecondary = palette.bg,
        tertiary = palette.amber,
        onTertiary = palette.bg,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.panel,
        onSurface = palette.text,
        surfaceVariant = palette.panelAlt,
        onSurfaceVariant = palette.textDim,
        error = palette.red,
        onError = palette.bg,
        outline = palette.stroke,
        outlineVariant = palette.strokeDim,
    )

    // Every stock Material style is re-pointed at B612 Mono so dialogs and text fields match the
    // panel without each call site having to say so.
    val base = TextStyle(fontFamily = B612Mono)
    val typography = Typography(
        displayLarge = base.copy(fontSize = FdType.ident),
        displayMedium = base.copy(fontSize = FdType.frequency),
        displaySmall = base.copy(fontSize = FdType.wordmark),
        headlineLarge = base.copy(fontSize = FdType.wordmark),
        headlineMedium = base.copy(fontSize = FdType.screenTitle),
        headlineSmall = base.copy(fontSize = FdType.screenTitle, letterSpacing = FdTracking.title),
        titleLarge = base.copy(fontSize = FdType.screenTitle),
        titleMedium = base.copy(fontSize = FdType.rowTitle),
        titleSmall = base.copy(fontSize = FdType.rowTitle),
        bodyLarge = base.copy(fontSize = FdType.body),
        bodyMedium = base.copy(fontSize = FdType.body),
        bodySmall = base.copy(fontSize = FdType.control),
        labelLarge = base.copy(fontSize = FdType.control, letterSpacing = FdTracking.control),
        labelMedium = base.copy(fontSize = FdType.control),
        labelSmall = base.copy(fontSize = FdType.sectionLabel),
    )

    CompositionLocalProvider(LocalFlightDeck provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}
