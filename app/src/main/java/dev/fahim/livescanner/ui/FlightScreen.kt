package dev.fahim.livescanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.Aircraft
import dev.fahim.livescanner.data.Compliance
import dev.fahim.livescanner.data.FactSource
import dev.fahim.livescanner.data.Instruction
import dev.fahim.livescanner.data.InstructionKind
import dev.fahim.livescanner.data.complianceOf
import dev.fahim.livescanner.data.FlightPhase
import dev.fahim.livescanner.data.Transmission
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck

/** After this long without an ADS-B hit, the panel stops presenting the track as current. */
private const val STALE_CONTACT_MS = 60_000L

/**
 * Follow one flight.
 *
 * Every other screen watches a field and everything in it. This one is deliberately the opposite:
 * type a flight number and the panel shows that aircraft and nothing else — where it is, which
 * runway it is using, which gate it is going to, and only the transmissions addressed to it.
 */
@Composable
fun FlightScreen(vm: MainViewModel, onBack: () -> Unit) {
    val flight by vm.flight.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val p = FlightDeck

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "FLIGHT FOLLOW",
            subtitle = flight.callsign ?: playback.feed?.displayCode?.let { "$it · NO FLIGHT" } ?: "—",
            onBack = onBack,
        ) {
            if (flight.following) {
                FdChip(label = "CLEAR", accent = FdAccent.RED, onClick = vm::clearFlight)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FdDim.gutter)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SearchCard(
                query = flight.query,
                error = flight.error,
                suggestions = flight.suggestions,
                onQuery = vm::setFlightQuery,
                onFollow = { vm.followFlight() },
                onPick = { vm.followFlight(it) },
            )

            if (flight.following) {
                IdentityCard(flight)
                FactGrid(flight)
                if (flight.instructions.isNotEmpty()) {
                    InstructionCard(flight.instructions, flight.aircraft)
                }
                ChatterCard(
                    transmissions = flight.transmissions,
                    callsign = flight.callsign,
                    playingId = flight.playingId,
                    playingPct = flight.playingPct,
                    onReplay = vm::replay,
                )
            }
        }
    }
}

/** The search box, its suggestions, and whatever went wrong with the last attempt. */
@Composable
private fun SearchCard(
    query: String,
    error: String?,
    suggestions: List<String>,
    onQuery: (String) -> Unit,
    onFollow: () -> Unit,
    onPick: (String) -> Unit,
) {
    val p = FlightDeck
    PanelCard(Modifier.fillMaxWidth()) {
        SectionLabel("FLIGHT NUMBER OR TAIL", Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "DL450 · UAL328 · N123DL",
                        fontFamily = B612Mono,
                        fontSize = FdType.control,
                        letterSpacing = FdTracking.control,
                        color = p.textGhost,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontFamily = B612Mono,
                        fontSize = FdType.rowTitle,
                        letterSpacing = FdTracking.control,
                        color = p.textHi,
                    ),
                    // Callsigns are uppercase, so the keyboard shouldn't fight that, and enter
                    // should follow the flight rather than type a newline into a flight number.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onFollow() }),
                    cursorBrush = SolidColor(p.cyan),
                )
            }
            Spacer(Modifier.width(12.dp))
            FdKey(
                label = "FOLLOW",
                active = false,
                accent = if (query.isBlank()) FdAccent.NEUTRAL else FdAccent.CYAN,
                enabled = query.isNotBlank(),
                onClick = onFollow,
            )
        }

        if (error != null) {
            PanelText(error, Modifier.padding(top = 10.dp), color = p.red, size = FdType.control)
        }

        if (suggestions.isNotEmpty()) {
            SectionLabel("HEARD NEARBY", Modifier.padding(top = 14.dp, bottom = 6.dp))
            // Capped at six by the view model — a list read from a car cannot be longer.
            suggestions.forEach { callsign ->
                FdKey(
                    label = callsign,
                    active = false,
                    accent = FdAccent.NEUTRAL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    onClick = { onPick(callsign) },
                )
            }
        }
    }
}

/** Who the flight is, and what it is doing right now. */
@Composable
private fun IdentityCard(flight: FlightUiState) {
    val p = FlightDeck
    val stale = flight.aircraft == null ||
        System.currentTimeMillis() - flight.lastContactMs > STALE_CONTACT_MS

    PanelCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PanelText(
                    flight.callsign.orEmpty(),
                    color = p.textHi,
                    bold = true,
                    size = FdType.frequency,
                    tracking = FdTracking.ident,
                    maxLines = 1,
                )
                PanelText(
                    listOfNotNull(flight.airline, flight.aircraftType, flight.aircraft?.registration)
                        .joinToString(" · ")
                        .ifBlank { "NO ADS-B CONTACT" },
                    modifier = Modifier.padding(top = 4.dp),
                    color = p.textFaint,
                    size = FdType.control,
                    maxLines = 2,
                )
            }
            StatusDot(live = !stale)
            Spacer(Modifier.width(10.dp))
            FdChip(label = flight.phase.label, accent = accentFor(flight.phase))
        }

        if (stale) {
            PanelText(
                // Out of ADS-B range is normal, not an error: the app polls around the tuned
                // field, so a flight an hour out simply isn't in the picture yet.
                "No ADS-B contact — out of range of the tuned field, or not transmitting. " +
                    "Radio chatter below still follows it.",
                modifier = Modifier.padding(top = 10.dp),
                color = p.textFaint,
                size = FdType.control,
            )
        }
    }
}

private fun accentFor(phase: FlightPhase): FdAccent = when (phase) {
    FlightPhase.APPROACH, FlightPhase.DEPARTURE -> FdAccent.GREEN
    FlightPhase.PARKED, FlightPhase.TAXI -> FdAccent.AMBER
    else -> FdAccent.NEUTRAL
}

/**
 * What the flight has been told to do, and whether it has done it.
 *
 * This is the one card that needs both halves of the app: the instruction comes off the radio,
 * the answer to "has it complied?" comes off the transponder. Neither source shows it alone.
 */
@Composable
private fun InstructionCard(instructions: Map<InstructionKind, Instruction>, aircraft: Aircraft?) {
    val p = FlightDeck
    PanelCard(Modifier.fillMaxWidth()) {
        SectionLabel("CLEARED TO", Modifier.padding(bottom = 4.dp))
        // Fixed order, so a readout glanced at from a car doesn't reshuffle between transmissions.
        InstructionKind.entries.forEach { kind ->
            val instruction = instructions[kind] ?: return@forEach
            val compliance = complianceOf(instruction, aircraft)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelText(
                    kind.label,
                    modifier = Modifier.width(46.dp),
                    color = p.textGhost,
                    size = FdType.sectionLabel,
                    maxLines = 1,
                )
                PanelText(
                    instruction.value,
                    modifier = Modifier.weight(1f),
                    color = p.textHi,
                    bold = true,
                    size = FdType.rowTitle,
                    maxLines = 1,
                )
                when (compliance) {
                    // "WORKING" rather than a failure: an aircraft mid-turn has not disobeyed
                    // anything, it just hasn't got there yet.
                    Compliance.MET -> FdChip("ESTABLISHED", FdAccent.GREEN)
                    Compliance.WORKING -> FdChip(nowLabel(kind, aircraft), FdAccent.AMBER)
                    Compliance.UNKNOWN -> Unit
                }
            }
        }
    }
}

/** What the aircraft is actually doing, for the instruction it hasn't settled onto yet. */
private fun nowLabel(kind: InstructionKind, ac: Aircraft?): String = when (kind) {
    InstructionKind.ALTITUDE -> ac?.altitudeFt?.let { "AT $it" } ?: "WORKING"
    InstructionKind.HEADING -> ac?.let { "AT ${it.trackDeg.toInt().toString().padStart(3, '0')}" } ?: "WORKING"
    InstructionKind.SPEED -> ac?.let { "AT ${it.groundSpeedKt.toInt()}" } ?: "WORKING"
    else -> "WORKING"
}

/** Runway, gate, heading, range — the four things worth a glance. */
@Composable
private fun FactGrid(flight: FlightUiState) {
    val ac = flight.aircraft
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FactTile(
                label = "RUNWAY",
                value = flight.runway ?: "—",
                note = when (flight.runwaySource) {
                    FactSource.ADSB -> "SEEN ON TRACK"
                    FactSource.RADIO -> "HEARD ON THE RADIO"
                    null -> "NOT ASSIGNED YET"
                },
                known = flight.runway != null,
                modifier = Modifier.weight(1f),
            )
            FactTile(
                label = "GATE",
                value = flight.gate ?: "—",
                // Nothing in ADS-B carries a gate, so this is only ever known because a
                // controller said it out loud while the app was listening.
                note = if (flight.gate != null) "HEARD ON THE RADIO" else "NOT ASSIGNED YET",
                known = flight.gate != null,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FactTile(
                label = "HEADING",
                value = flight.headingLabel ?: "—",
                note = ac?.let { "${it.groundSpeedKt.toInt()} KT" } ?: "NO CONTACT",
                known = ac != null,
                modifier = Modifier.weight(1f),
            )
            FactTile(
                label = "POSITION",
                value = flight.positionLabel ?: "—",
                note = when {
                    ac == null -> "NO CONTACT"
                    ac.onGround -> "ON GROUND"
                    else -> ac.altitudeFt?.let { "$it FT" } ?: "ALTITUDE UNKNOWN"
                },
                known = ac != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FactTile(
    label: String,
    value: String,
    note: String,
    known: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = FlightDeck
    PanelCard(modifier) {
        SectionLabel(label)
        PanelText(
            value,
            modifier = Modifier.padding(top = 6.dp),
            // An unknown reads as dimmed rather than absent, so the tile still holds its place.
            color = if (known) p.textHi else p.textGhost,
            bold = true,
            size = FdType.screenTitle,
            maxLines = 1,
        )
        PanelText(note, Modifier.padding(top = 4.dp), color = p.textGhost, size = FdType.sectionLabel, maxLines = 2)
    }
}

/** Only this flight's transmissions, newest first. Tap one to hear it. */
@Composable
private fun ChatterCard(
    transmissions: List<Transmission>,
    callsign: String?,
    playingId: String?,
    playingPct: Int,
    onReplay: (String) -> Unit,
) {
    val p = FlightDeck
    PanelCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("CHATTER FOR THIS FLIGHT", Modifier.weight(1f))
            PanelText("${transmissions.size}", color = p.textFaint, size = FdType.control, maxLines = 1)
        }

        if (transmissions.isEmpty()) {
            PanelText(
                "Nothing heard yet. Transmissions naming ${callsign ?: "this flight"} appear here " +
                    "as they are transcribed — including when it is called by name, like " +
                    "\"Delta four fifty\". Tap one to hear it.",
                modifier = Modifier.padding(top = 10.dp),
                color = p.textFaint,
                size = FdType.control,
            )
            return@PanelCard
        }

        transmissions.forEach { entry ->
            val playing = entry.id == playingId
            // Clips age out of the rolling buffer; without audio behind it the row is still
            // worth reading, it just isn't worth offering as a button.
            val playable = entry.bufferOffset != null
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (playable) Modifier.clickable { onReplay(entry.id) } else Modifier)
                    // Comfortably tappable from a car without stretching short rows.
                    .heightIn(min = 56.dp)
                    .padding(top = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PanelText(entry.clockLabel, color = p.textGhost, size = FdType.sectionLabel, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    PanelText(entry.feedLabel, color = p.textFaint, size = FdType.sectionLabel, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    PanelText(
                        when {
                            playing -> "PLAYING"
                            playable -> "TAP TO PLAY"
                            else -> "AGED OUT"
                        },
                        color = if (playing) p.green else p.textGhost,
                        size = FdType.sectionLabel,
                        maxLines = 1,
                    )
                }
                PanelText(
                    entry.raw,
                    Modifier.padding(top = 4.dp),
                    color = if (playing) p.textHi else p.text,
                    size = FdType.body,
                )
                entry.plainEnglish?.let {
                    PanelText(it, Modifier.padding(top = 3.dp), color = p.cyan, size = FdType.paraphrase)
                }
                if (playing) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(2.dp)
                            .background(p.strokeDim),
                    ) {
                        Box(
                            Modifier
                                // Never zero-width: a playhead that vanishes at the start reads
                                // as the tap having done nothing.
                                .fillMaxWidth((playingPct / 100f).coerceIn(0.02f, 1f))
                                .height(2.dp)
                                .background(p.green),
                        )
                    }
                }
            }
        }
    }
}
