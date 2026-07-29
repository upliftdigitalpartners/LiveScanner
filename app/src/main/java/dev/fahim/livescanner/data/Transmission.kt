package dev.fahim.livescanner.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOCAL_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** How loudly a transmission should read in the recorder list. */
@Serializable
enum class Priority { EMERGENCY, NOTABLE, ROUTINE }

/**
 * One captured radio transmission: what was said, when, on which feed, and where its audio sits
 * in the rolling buffer so it can be replayed or exported as a clip.
 */
@Serializable
data class Transmission(
    val id: String,
    val timestampMs: Long,
    val feedId: String,
    val feedLabel: String,
    val durationMs: Long,
    val raw: String,
    val plainEnglish: String? = null,
    val callsign: String? = null,
    val priority: Priority = Priority.ROUTINE,
    /** Byte offset of this clip inside the rolling buffer, or null once it has aged out. */
    val bufferOffset: Long? = null,
    val bufferLength: Int = 0,
    /** 34 normalised amplitudes (0..1) — the collapsed/expanded waveform in the recorder card. */
    val waveform: List<Float> = emptyList(),
) {
    /** hh:mm:ss in the device's local time, as shown on the card's top line. */
    val clockLabel: String
        get() = LOCAL_CLOCK.format(Instant.ofEpochMilli(timestampMs))

    /** m:ss duration, e.g. "0:11". */
    val durationLabel: String
        get() {
            val totalSeconds = (durationMs / 1000).toInt()
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }
}

/** Words that promote a transmission to EMERGENCY without any user rule being armed. */
private val EMERGENCY_PHRASES = listOf(
    "MAYDAY", "PAN-PAN", "PAN PAN", "DECLARING", "SQUAWK 7700", "EMERGENCY",
)

/** Words that make a transmission NOTABLE — worth a cyan rule but not an alarm. */
private val NOTABLE_PHRASES = listOf(
    "GO AROUND", "MEDFLIGHT", "LIFEGUARD", "MISSED APPROACH", "TRAFFIC ALERT", "PRIORITY",
)

fun priorityFor(transcript: String): Priority {
    val upper = transcript.uppercase()
    return when {
        EMERGENCY_PHRASES.any { it in upper } -> Priority.EMERGENCY
        NOTABLE_PHRASES.any { it in upper } -> Priority.NOTABLE
        else -> Priority.ROUTINE
    }
}
