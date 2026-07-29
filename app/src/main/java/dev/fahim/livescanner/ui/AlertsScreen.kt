package dev.fahim.livescanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.AlertRule
import dev.fahim.livescanner.data.RuleAccent
import dev.fahim.livescanner.data.RuleType
import dev.fahim.livescanner.ui.theme.B612Mono
import dev.fahim.livescanner.ui.theme.FdDim
import dev.fahim.livescanner.ui.theme.FdTracking
import dev.fahim.livescanner.ui.theme.FdType
import dev.fahim.livescanner.ui.theme.FlightDeck

/** A disarmed rule keeps its glyph, but drained of hue — present, not watching. */
private val GlyphDim = Color(0xFF3A4A5A)

/**
 * The armed-watch list: every rule the transcriber is matching against, each on its own switched
 * card, with a dashed well at the bottom for arming a new one. Seeded rules can only be toggled;
 * user-created ones (id "user:…") are removed by long-pressing the card.
 *
 * The fired-alert banner is not drawn here — the app shell overlays it above every screen.
 */
@Composable
fun AlertsScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val state by vm.alerts.collectAsStateWithLifecycle()
    val armed = state.rules.count { it.on }

    Column(
        Modifier
            .fillMaxSize()
            .background(FlightDeck.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "ALERT RULES",
            subtitle = "$armed OF ${state.rules.size} RULES ARMED",
            onBack = onBack,
            trailing = {
                FdChip(label = "TEST FIRE", accent = FdAccent.AMBER, onClick = { vm.testFire() })
            },
        )

        Column(
            Modifier.padding(horizontal = FdDim.gutter),
            verticalArrangement = Arrangement.spacedBy(FdDim.rowPadding),
        ) {
            state.rules.forEach { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { vm.toggleRule(rule.id) },
                    onRemove = { vm.removeRule(rule.id) },
                )
            }

            NewRuleWell(
                ruleType = state.ruleType,
                onRuleType = vm::setRuleType,
                onArm = vm::armRule,
                modifier = Modifier.padding(top = FdDim.rowPadding),
            )

            Spacer(Modifier.height(FdDim.gutter))
        }
    }
}

@Composable
private fun RuleCard(rule: AlertRule, onToggle: () -> Unit, onRemove: () -> Unit) {
    val p = FlightDeck
    val removable = rule.id.startsWith("user:")

    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (removable) {
                    Modifier.pointerInput(rule.id) {
                        detectTapGestures(onLongPress = { onRemove() })
                    }
                } else {
                    Modifier
                },
            ),
        borderColor = if (rule.on) null else p.strokeDim,
        fill = if (rule.on) null else p.panelAlt,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                PanelText(
                    text = glyphFor(rule.accent),
                    color = if (rule.on) accentColor(rule.accent) else GlyphDim,
                    size = FdType.screenTitle,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                PanelText(
                    text = rule.name,
                    color = p.textHi,
                    bold = true,
                    size = FdType.rowTitle,
                    maxLines = 1,
                )
                PanelText(
                    text = rule.detail,
                    modifier = Modifier.padding(top = 3.dp),
                    color = p.textFaint,
                    size = FdType.control,
                    maxLines = 2,
                )
            }
            PillSwitch(on = rule.on, onToggle = onToggle)
        }
    }
}

@Composable
private fun NewRuleWell(
    ruleType: RuleType,
    onRuleType: (RuleType) -> Unit,
    onArm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = FlightDeck
    val focus = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf("") }

    val submit = {
        val text = draft.trim().uppercase()
        if (text.isNotEmpty()) {
            onArm(text)
            draft = ""
            focus.clearFocus()
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // No dashed-border modifier exists; stroke the outline ourselves, inset by half the
                // line width so neither edge is clipped by the layout bounds.
                val line = 1.dp.toPx()
                val radius = FdDim.radiusCard.toPx()
                drawRoundRect(
                    color = p.strokeInput,
                    topLeft = Offset(line / 2f, line / 2f),
                    size = Size(size.width - line, size.height - line),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(
                        width = line,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    ),
                )
            }
            .padding(FdDim.cardPadding),
        verticalArrangement = Arrangement.spacedBy(FdDim.rowPadding),
    ) {
        SectionLabel("NEW RULE")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleType.entries.forEach { type ->
                FdKey(
                    label = segmentLabel(type),
                    active = type == ruleType,
                    accent = FdAccent.CYAN,
                    modifier = Modifier.weight(1f),
                    onClick = { onRuleType(type) },
                )
            }
        }

        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = B612Mono,
                fontSize = FdType.body,
                letterSpacing = FdTracking.control,
                color = p.textHi,
            ),
            singleLine = true,
            cursorBrush = SolidColor(p.cyan),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FdDim.radiusControl))
                        .background(p.panelAlt)
                        .border(1.dp, p.strokeInput, RoundedCornerShape(FdDim.radiusControl))
                        .padding(horizontal = 12.dp, vertical = FdDim.controlPaddingV),
                ) {
                    if (draft.isEmpty()) {
                        PanelText(
                            text = placeholderFor(ruleType),
                            color = p.textGhost,
                            size = FdType.body,
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelText(
                text = "SCOPE · ALL ACTIVE FEEDS",
                modifier = Modifier.weight(1f),
                color = p.textFaint,
                size = FdType.control,
                maxLines = 1,
            )
            FdKey(
                label = "+ ARM RULE",
                active = draft.isNotBlank(),
                accent = FdAccent.GREEN,
                enabled = draft.isNotBlank(),
                onClick = submit,
            )
        }
    }
}

/** One character per accent, standing in for an icon set the deck deliberately doesn't have. */
private fun glyphFor(accent: RuleAccent): String = when (accent) {
    RuleAccent.RED -> "⚠"
    RuleAccent.CYAN -> "✈"
    RuleAccent.MAGENTA -> "⌕"
    RuleAccent.GREEN -> "✚"
    RuleAccent.AMBER -> "★"
}

@Composable
private fun accentColor(accent: RuleAccent): Color {
    val p = FlightDeck
    return when (accent) {
        RuleAccent.RED -> p.red
        RuleAccent.CYAN -> p.cyan
        RuleAccent.MAGENTA -> p.magenta
        RuleAccent.GREEN -> p.green
        RuleAccent.AMBER -> p.amber
    }
}

private fun segmentLabel(type: RuleType): String = when (type) {
    RuleType.KEYWORD -> "KEYWORD"
    RuleType.TAIL -> "TAIL #"
    RuleType.FEED -> "FEED"
}

private fun placeholderFor(type: RuleType): String = when (type) {
    RuleType.KEYWORD -> "WORD OR PHRASE TO WATCH FOR…"
    RuleType.TAIL -> "TAIL NUMBER, E.G. N425KH"
    RuleType.FEED -> "FEED NAME OR CODE"
}
