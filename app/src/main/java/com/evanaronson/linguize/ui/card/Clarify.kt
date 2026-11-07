package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.Checker
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.Chevron
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField

/**
 * The one way to say what you meant, wherever a reading can be clarified: under a
 * change's explanation, on an assumption in Meaning, for the whole message on Meaning,
 * and when the AI couldn't tell what the text means.
 *
 * "What did you mean?", then the readings to pick from ([choices]: the AI's reading
 * first, then its alternatives) and Something else, which opens a field; with no
 * choices, just the field. Clarify stays disabled until there is an answer, and is the
 * card's primary action while this is open. Nothing changes the check until Clarify.
 */
@Composable
internal fun ClarifyBlock(
    choices: List<String>,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialAnswer: String = "",
    /** The writer's own words are the main content when there is nothing to pick. */
    large: Boolean = choices.isEmpty(),
    /** Labels the field for screen readers when the block stands for one assumption. */
    about: String? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val other = choices.size
    var picked by rememberSaveable(choices) { mutableStateOf(if (choices.isEmpty()) other else -1) }
    var answer by rememberSaveable(initialAnswer) { mutableStateOf(initialAnswer) }
    val typing = picked == other
    val trimmed = answer.trim()
    val tooLong = typing && trimmed.length > Checker.MAX_CHARS
    val result = when {
        picked in choices.indices -> choices[picked]
        typing && trimmed.isNotEmpty() && !tooLong -> trimmed
        else -> null
    }
    fun finish(action: () -> Unit) {
        focus.clearFocus()
        keyboard?.hide()
        action()
    }
    Column(modifier) {
        Text(
            stringResource(R.string.what_did_you_mean),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
        )
        if (choices.isNotEmpty()) {
            Column(Modifier.padding(top = 2.dp).selectableGroup()) {
                choices.forEachIndexed { index, choice ->
                    ClarifyOption(choice, picked == index) { picked = index }
                }
                ClarifyOption(stringResource(R.string.something_else), typing) { picked = other }
            }
        }
        if (typing) {
            val focusRequester = remember { FocusRequester() }
            // The field appears only when the writer asked for it, so it can take focus.
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            val label = about?.let { stringResource(R.string.clarify_about, it) }
                ?: stringResource(R.string.what_did_you_mean)
            LinguizeTextField(
                value = answer,
                onValueChange = { answer = it },
                modifier = Modifier.padding(top = 6.dp),
                fieldModifier = Modifier
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = label },
                placeholder = stringResource(R.string.clarify_placeholder),
                large = large,
                minLines = if (large) 2 else 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                isError = tooLong,
                supporting = if (tooLong) stringResource(R.string.clarification_too_long, Checker.MAX_CHARS) else null,
            )
        }
        Row(
            Modifier.padding(top = if (choices.isEmpty()) 8.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinguizeButton(
                stringResource(R.string.clarify),
                onClick = { result?.let { finish { onSubmit(it) } } },
                kind = ButtonKind.Filled,
                enabled = result != null,
            )
            LinguizeButton(stringResource(R.string.cancel), onClick = { finish(onCancel) }, kind = ButtonKind.Text)
        }
    }
}

/** A reading to pick: muted until picked, then ink. */
@Composable
private fun ClarifyOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.onSurface,
                unselectedColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * An assumption on Meaning: its subject, the reading in italic, and the words it rests on,
 * on a grey inset. When [onSettle] is set the inset is the way in: tap it and the
 * [ClarifyBlock] opens inside it.
 */
@Composable
internal fun AssumptionInset(assumption: Assumption, onSettle: ((String, String) -> Unit)?) {
    var open by rememberSaveable(assumption) { mutableStateOf(false) }
    val clarify = stringResource(R.string.clarify_about, assumption.about)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(InsetShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (onSettle != null && !open) {
                        Modifier.clickable(onClickLabel = clarify) { open = true }
                            .semantics { contentDescription = clarify }
                    } else Modifier,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Reading(assumption.about, assumption.assumed)
                if (assumption.words.isNotBlank()) {
                    Text(
                        stringResource(R.string.quoted_text, assumption.words),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onSettle != null && !open) Chevron()
        }
        if (onSettle != null && open) {
            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ClarifyBlock(
                choices = assumptionChoices(assumption),
                onSubmit = { answer ->
                    open = false
                    onSettle(assumption.about, answer)
                },
                onCancel = { open = false },
                about = assumption.about,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            )
        }
    }
}

/** A subject over a reading in italic: how an assumption or an answer is written. */
@Composable
internal fun Reading(about: String, reading: String) {
    Text(about, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(reading, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
}

/** Include the current reading so clarification can confirm it as well as correct it. */
internal fun assumptionChoices(assumption: Assumption): List<String> =
    (listOf(assumption.assumed) + assumption.alternatives).filter { it.isNotBlank() }.distinct()
