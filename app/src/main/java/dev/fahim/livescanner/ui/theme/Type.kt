package dev.fahim.livescanner.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fahim.livescanner.R

/** B612 Mono — the ESA cockpit typeface, bundled as TTFs (never fetched at runtime). */
val B612Mono = FontFamily(
    Font(R.font.b612_mono_regular, FontWeight.Normal),
    Font(R.font.b612_mono_bold, FontWeight.Bold),
)

/**
 * Type scale. The spec's px values come from a browser prototype and are relative, not dp —
 * every one is raised by 1.3x here, keeping the proportions intact.
 */
object FdType {
    val ident = 39.sp          // 30px — now-playing ident
    val frequency = 33.8.sp    // 26px — now-playing frequency
    val wordmark = 19.5.sp     // 15px — app wordmark
    val screenTitle = 16.9.sp  // 13px
    val rowTitle = 15.6.sp     // 12px
    val body = 14.3.sp         // 11px
    val control = 13.sp        // 10px
    val sectionLabel = 11.7.sp // 9px
    val dataTag = 11.05.sp     // 8.5px — radar tags
    val paraphrase = 12.35.sp  // 9.5px — plain-English line in history
}

/** Tracking (letter spacing) — everything in this UI is uppercase and widely tracked. */
object FdTracking {
    val ident = 1.3.sp
    val wordmark = 2.6.sp
    val title = 1.3.sp
    val control = 1.3.sp
    val section = 2.6.sp
}

/** Spacing and radii, also scaled 1.3x off the prototype. */
object FdDim {
    val gutter = 18.dp          // 14px screen gutter
    val cardPadding = 18.dp     // 14px
    val cardPaddingTight = 16.dp // 12px
    val rowPadding = 13.dp      // 10px
    val controlPaddingV = 11.dp // 7-10px

    val radiusChip = 5.dp       // 4px
    val radiusControl = 6.dp    // 5px
    val radiusRow = 8.dp        // 6px
    val radiusCard = 10.dp      // 8px
}
