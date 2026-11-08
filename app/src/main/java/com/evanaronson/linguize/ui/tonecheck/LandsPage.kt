package com.evanaronson.linguize.ui.tonecheck

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingRisk
import com.evanaronson.linguize.core.lab.LandingVersion
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Outcome
import com.evanaronson.linguize.ui.card.PageHeader
import com.evanaronson.linguize.ui.card.Progress
import com.evanaronson.linguize.ui.card.ResultSurface
import com.evanaronson.linguize.ui.card.SectionDivider
import com.evanaronson.linguize.ui.card.SuccessLine
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.copyToClipboard

/** A controller that lives as long as the composition that remembers it. */
@Composable
fun rememberLands(analyze: suspend (String) -> Landing): LandsController {
    val latest by rememberUpdatedState(analyze)
    val scope = rememberCoroutineScope()
    return remember(scope) { LandsController(scope) { latest(it) } }
}

/** The check card's footer row that opens "How it lands". */
@Composable
fun LandsEntry(actions: LandsActions, onOpen: () -> Unit) {
    FooterRow(
        stringResource(R.string.lab_tone_check_title),
        stringResource(
            if (actions.chosen() != null) R.string.lab_tone_check_entry_subtitle_chosen
            else R.string.lab_tone_check_entry_subtitle,
        ),
        onClick = onOpen,
        onClickLabel = stringResource(R.string.lab_tone_check_open),
    )
}

/**
 * "How it lands" as the check card's own page, for the accepted [text]. Always call it, so
 * its reading survives going back to the suggestions; it draws only while [showing], and
 * returns whether it did. The card then draws nothing else.
 */
@Composable
fun showLandsPage(
    actions: LandsActions?,
    text: String?,
    showing: Boolean,
    onShowing: (Boolean) -> Unit,
    modifier: Modifier,
    scrollable: Boolean,
): Boolean {
    val controller = rememberLands { actions?.analyze?.invoke(it) ?: error("How it lands is not offered here") }
    val visible = showing && actions != null && text != null
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = visible) { onShowing(false) }
    }
    if (!visible) return false
    ResultSurface(modifier, scrollable, navigationKey = "lands") {
        LandsPage(controller, text, actions, onBack = { onShowing(false) })
    }
    return true
}

/** The page's content: the header, then loading, the failure, or the reading. */
@Composable
fun ColumnScope.LandsPage(controller: LandsController, text: String, actions: LandsActions, onBack: () -> Unit) {
    val message = text.trim()
    LaunchedEffect(message) { controller.open(message) }
    PageHeader(stringResource(R.string.lab_tone_check_title), onBack)
    when (val state = controller.state) {
        is LandsState.Done -> if (state.text == message) {
            LandingContent(state.landing, actions)
        } else {
            Progress(stringResource(R.string.lab_tone_check_loading))
        }
        is LandsState.Failed -> if (state.text == message) {
            Failure(state.failure.reason, state.failure.userMessage, state.failure.detail, controller::retry, actions.onOpenSettings)
        } else {
            Progress(stringResource(R.string.lab_tone_check_loading))
        }
        else -> Progress(stringResource(R.string.lab_tone_check_loading))
    }
}

@Composable
private fun ColumnScope.LandingContent(landing: Landing, actions: LandsActions) {
    Outcome(stringResource(riskTitle(landing.risk)), landing.summary, success = landing.risk == LandingRisk.Fine)
    if (landing.variety.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.lab_tone_check_read_as, landing.variety),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    Scale(R.string.lab_tone_check_warmth, landing.warmth, R.string.lab_tone_check_cold, R.string.lab_tone_check_warm)
    Spacer(Modifier.height(12.dp))
    Scale(R.string.lab_tone_check_directness, landing.directness, R.string.lab_tone_check_indirect, R.string.lab_tone_check_blunt)
    Spacer(Modifier.height(12.dp))
    Scale(R.string.lab_tone_check_formality, landing.formality, R.string.lab_tone_check_casual, R.string.lab_tone_check_formal)

    if (landing.drivers.isNotEmpty()) {
        SectionDivider()
        Heading(stringResource(R.string.lab_tone_check_drivers))
        landing.drivers.forEach { driver ->
            Spacer(Modifier.height(8.dp))
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(R.string.lab_tone_check_quote, driver.quote),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                )
                Text(driver.effect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (landing.notes.isNotEmpty()) {
        SectionDivider()
        Heading(stringResource(R.string.lab_tone_check_notes))
        landing.notes.forEach { note ->
            Spacer(Modifier.height(6.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }

    SectionDivider()
    Heading(stringResource(R.string.lab_tone_check_versions))
    if (landing.versions.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Quiet(stringResource(R.string.lab_tone_check_no_versions))
    }
    val chosen = actions.chosen()
    landing.versions.forEach { version ->
        Spacer(Modifier.height(10.dp))
        Version(version, chosen == version.text, actions)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.lab_tone_check_caveat),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() })
}

/** One reading on a five-step scale, spoken as a single phrase. */
@Composable
private fun Scale(label: Int, value: Int, low: Int, high: Int) {
    val name = stringResource(label)
    val lowText = stringResource(low)
    val highText = stringResource(high)
    val description = stringResource(R.string.lab_tone_check_scale_description, name, value, Landing.SCALE_MAX, lowText, highText)
    val filled = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (step in Landing.SCALE_MIN..Landing.SCALE_MAX) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (step <= value) filled else track),
                    )
                }
            }
        }
        Row(Modifier.padding(start = 96.dp, top = 2.dp)) {
            Text(lowText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(highText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Version(version: LandingVersion, chosen: Boolean, actions: LandsActions) {
    Column {
        Text(version.label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        TextBlock(version.text)
        if (version.why.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(version.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        val use = actions.onUse
        if (chosen && use != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SuccessLine(stringResource(R.string.lab_tone_check_chosen), quiet = true) }
                LinguizeButton(stringResource(R.string.undo), { use(null) }, ButtonKind.Text)
                actions.onApply?.let { LinguizeButton(stringResource(R.string.apply), it, ButtonKind.Filled) }
            }
        } else {
            val useLabel = stringResource(R.string.lab_tone_check_use_description, version.label)
            val copyLabel = stringResource(R.string.lab_tone_check_copy_description, version.label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                use?.let {
                    LinguizeButton(stringResource(R.string.lab_tone_check_use), { it(version.text) }, ButtonKind.Tonal,
                        Modifier.semantics { contentDescription = useLabel })
                }
                LinguizeButton(stringResource(R.string.lab_tone_check_copy), { actions.onCopy(version.text) }, ButtonKind.Text,
                    Modifier.semantics { contentDescription = copyLabel })
            }
        }
    }
}

private fun riskTitle(risk: LandingRisk) = when (risk) {
    LandingRisk.Fine -> R.string.lab_tone_check_risk_fine
    LandingRisk.WorthALook -> R.string.lab_tone_check_risk_worth_a_look
    LandingRisk.Risky -> R.string.lab_tone_check_risk_risky
}

/**
 * Home's Try it: "How it lands" for the text in the box, without checking it first. A
 * version taken here replaces the box's text; editing the box closes the reading.
 */
@Composable
fun TryItLands(sample: String, onUse: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val controller = rememberLands { App.of(context).landing.landing(it) }
    var analyzed by rememberSaveable { mutableStateOf<String?>(null) }
    val text = sample.trim()
    LaunchedEffect(text) {
        if (analyzed != null && analyzed != text) {
            analyzed = null
            controller.close()
        }
    }
    val shown = analyzed
    val openLabel = stringResource(R.string.lab_tone_check_open)
    if (shown == null) {
        LinguizeButton(
            stringResource(R.string.lab_tone_check_title),
            { analyzed = text },
            ButtonKind.Outlined,
            Modifier.padding(top = 8.dp).semantics { contentDescription = openLabel },
            enabled = text.isNotEmpty(),
        )
        return
    }
    val actions = LandsActions(
        analyze = { App.of(context).landing.landing(it) },
        onCopy = { context.copyToClipboard(it) },
        onUse = { version -> version?.let(onUse) },
        chosen = { null },
        onApply = null,
        onOpenSettings = onOpenSettings,
    )
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler { analyzed = null }
    }
    ResultSurface(Modifier.fillMaxWidth().padding(top = 12.dp), scrollable = false, navigationKey = shown) {
        LandsPage(controller, shown, actions, onBack = { analyzed = null })
    }
}
