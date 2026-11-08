package com.evanaronson.linguize.ui.practice

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Hint
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.ui.card.ExplanationPanel
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.Instant
import java.time.ZoneId

/** A practice run: one item at a time, feedback and the saved reason after each, a short summary at the end. */
@Composable
fun PracticeScreen(model: PracticeViewModel, onDone: () -> Unit) {
    val state by model.state.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    val loaded = state.items != null
    LaunchedEffect(loaded) {
        if (loaded && !started) {
            started = true
            if (state.run?.finished != false) model.start()
        }
    }
    val leave = {
        model.close()
        onDone()
    }
    ScreenColumn {
        val run = state.run
        when {
            state.items == null -> Message(stringResource(R.string.lab_practice_loading), null)
            run == null -> if (state.items.isNullOrEmpty()) {
                Message(stringResource(R.string.lab_practice_empty), stringResource(R.string.lab_practice_empty_hint))
            } else {
                Message(stringResource(R.string.lab_practice_nothing_due), nextLine(state) ?: stringResource(R.string.lab_practice_nothing_due_hint))
            }
            run.finished -> Done(run, state, leave)
            else -> {
                val item = run.current ?: return@ScreenColumn
                SectionHeader(stringResource(R.string.lab_practice_progress, run.position, run.steps)) {
                    LinguizeButton(stringResource(R.string.lab_practice_stop), leave, ButtonKind.Text)
                }
                SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                    // Fixed when the item appears: answering changes the review count it depends on.
                    val correctFirst = rememberSaveable(run.index) { state.correctFirst(item) }
                    ItemView(run, item, correctFirst, model)
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, hint: String?) {
    Spacer(Modifier.height(8.dp))
    SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let { Quiet(it, Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun ItemView(run: PracticeRun, item: PracticeItem, correctFirst: Boolean, model: PracticeViewModel) {
    val answered = run.answered
    // Keyed by position: a repeated item starts with an empty field.
    var typed by rememberSaveable(run.index) { mutableStateOf("") }
    when (item.mode) {
        PracticeItem.Mode.Type -> {
            Label(stringResource(com.evanaronson.linguize.ui.chat.youWroteLabel(item.origin)))
            MarkedSentence(item)
            if (answered == null) {
                Spacer(Modifier.height(16.dp))
                LinguizeTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = stringResource(R.string.lab_practice_type_prompt),
                    accessibleLabel = stringResource(R.string.lab_practice_type_label),
                    large = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    LinguizeButton(stringResource(R.string.lab_practice_show), model::reveal, ButtonKind.Text)
                    LinguizeButton(stringResource(R.string.lab_practice_check), { model.submit(typed) }, ButtonKind.Filled,
                        enabled = typed.isNotBlank())
                }
            }
        }
        PracticeItem.Mode.Choose -> {
            Label(stringResource(R.string.lab_practice_choose_prompt))
            val options = if (correctFirst) listOf(item.corrected to true, item.sentence to false)
                else listOf(item.sentence to false, item.corrected to true)
            options.forEach { (sentence, correct) ->
                Option(sentence, enabled = answered == null, marked = answered != null && correct) { model.choose(correct) }
            }
            if (answered == null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    LinguizeButton(stringResource(R.string.lab_practice_show), model::reveal, ButtonKind.Text)
                }
            }
        }
    }
    if (answered != null) {
        Feedback(item, answered, typed.takeIf { item.mode == PracticeItem.Mode.Type }, comesBack = item.id !in run.repeated)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            val last = run.index == run.queue.lastIndex && !(answered.missed && item.id !in run.repeated)
            LinguizeButton(
                stringResource(if (last) R.string.lab_practice_finish else R.string.lab_practice_next),
                model::next, ButtonKind.Filled,
            )
        }
    }
}

private val Answered.missed: Boolean get() = revealed || result == AnswerResult.Wrong || result == AnswerResult.Unchanged

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp))
}

/** The sentence as written, the mistaken words underlined in proof vermilion, as on the card. */
@Composable
private fun MarkedSentence(item: PracticeItem) {
    val proof = Linguize.colors.proof
    val spoken = item.sentence + "\n" + stringResource(R.string.lab_practice_mistake_marked, item.wrong)
    Text(
        buildAnnotatedString {
            append(item.sentence.substring(0, item.wrongStart))
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = proof)) { append(item.wrong) }
            append(item.sentence.substring(item.wrongEnd))
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

@Composable
private fun Option(sentence: String, enabled: Boolean, marked: Boolean, onClick: () -> Unit) {
    val border = if (marked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val label = stringResource(R.string.lab_practice_choose_option, sentence)
    Text(
        sentence,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .clip(InsetShape)
            .border(if (marked) 2.dp else 1.dp, border, InsetShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(12.dp),
    )
}

@Composable
private fun Feedback(item: PracticeItem, answered: Answered, typed: String?, comesBack: Boolean) {
    val result = answered.result
    val title = when {
        answered.revealed -> stringResource(R.string.lab_practice_revealed)
        result == AnswerResult.Correct -> stringResource(R.string.lab_practice_correct)
        result == AnswerResult.Unchanged -> stringResource(R.string.lab_practice_unchanged)
        result is AnswerResult.Almost -> hintText(result.hint)
        else -> stringResource(R.string.lab_practice_wrong)
    }
    Column(Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (answered.missed) Linguize.colors.proof else MaterialTheme.colorScheme.onSurface,
        )
        if (typed != null && !answered.revealed && result != AnswerResult.Correct) {
            Quiet(stringResource(R.string.lab_practice_answer, typed.trim()), Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(12.dp))
        ExplanationPanel(
            from = item.wrong,
            to = item.right,
            description = stringResource(R.string.change_becomes, item.wrong, item.right),
            why = item.why ?: stringResource(R.string.lab_practice_no_reason),
            assessment = item.assessment,
            assumptions = emptyList(),
            answers = emptyList(),
            accent = Linguize.colors.proof,
        )
        Spacer(Modifier.height(12.dp))
        TextBlock(item.corrected)
        if (answered.missed && comesBack) {
            Quiet(stringResource(R.string.lab_practice_coming_back), Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun hintText(hint: Hint): String = when (hint) {
    is Hint.MissingAccent -> stringResource(R.string.lab_practice_missing_accent, hint.word)
    is Hint.ExtraAccent -> stringResource(R.string.lab_practice_extra_accent, hint.word)
    is Hint.WrongAccent -> stringResource(R.string.lab_practice_wrong_accent, hint.word)
    Hint.Accents -> stringResource(R.string.lab_practice_accents)
    Hint.Punctuation -> stringResource(R.string.lab_practice_punctuation)
    Hint.Capitals -> stringResource(R.string.lab_practice_capitals)
    Hint.Spelling -> stringResource(R.string.lab_practice_spelling)
}

@Composable
private fun Done(run: PracticeRun, state: PracticeState, onDone: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.lab_practice_done), style = MaterialTheme.typography.titleMedium)
        Quiet(pluralStringResource(R.plurals.lab_practice_score, run.size, run.right, run.size), Modifier.padding(top = 4.dp))
        nextLine(state)?.let { Quiet(it) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            LinguizeButton(stringResource(R.string.lab_practice_back_home), onDone, ButtonKind.Filled)
        }
    }
}

/** "Next practice tomorrow", or null when nothing has been practised yet. */
@Composable
private fun nextLine(state: PracticeState): String? {
    val next = state.nextDue ?: return null
    val zone = ZoneId.systemDefault()
    val days = (Instant.ofEpochMilli(next).atZone(zone).toLocalDate().toEpochDay() - state.today).toInt()
    return when {
        days <= 0 -> stringResource(R.string.lab_practice_next_today)
        days == 1 -> stringResource(R.string.lab_practice_next_tomorrow)
        else -> pluralStringResource(R.plurals.lab_practice_next_days, days, days)
    }
}
