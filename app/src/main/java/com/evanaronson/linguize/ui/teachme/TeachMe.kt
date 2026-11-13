package com.evanaronson.linguize.ui.teachme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.core.teachme.QuizQuestion
import com.evanaronson.linguize.core.teachme.QuizState
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.Disclosure
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.text.hintResource
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * "Teach me" under one change's explanation: a header that opens a one-minute lesson in place
 * (the rule, why this version, three examples, the common trap and a two-question quiz).
 * Draws nothing when there's no [topic], or outside the Linguize app (previews and tests).
 */
@Composable
fun TeachMe(
    topic: LessonTopic?,
    modifier: Modifier = Modifier,
    /** Called as the lesson opens, before it asks for anything (Type to accept closes the keyboard). */
    onOpen: () -> Unit = {},
) {
    if (topic == null) return
    val teach = LocalLessonTeacher.current
        ?: (LocalContext.current.applicationContext as? App)?.teacher?.let { teacher -> teacher::teach }
        ?: return
    val scope = rememberCoroutineScope()
    val controller = remember(topic) { LessonController(topic, scope) { teach(it) } }
    TeachMeContent(controller, modifier, onOpen)
}

/** Who writes lessons; null uses the app's teacher. Lets tests give the card a fake one. */
val LocalLessonTeacher = staticCompositionLocalOf<(suspend (LessonTopic) -> Lesson)?> { null }

/**
 * The lesson's states, for [TeachMe] and for tests with a fake teacher. "Teach me" is a
 * [Disclosure] (docs/lab/expand-pattern.md): opening it asks for the lesson, and its content is
 * the loading line, the failure with Retry, or the lesson. Collapsing it is Hide.
 */
@Composable
internal fun TeachMeContent(controller: LessonController, modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val state = controller.state
        Disclosure(
            expanded = state != LessonState.Closed,
            onExpandedChange = { open ->
                if (open) {
                    onOpen()
                    controller.open()
                } else controller.close()
            },
            header = {
                Text(stringResource(R.string.lab_teach_me_open), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            },
            expandLabel = stringResource(R.string.lab_teach_me_open_action),
            collapseLabel = stringResource(R.string.lab_teach_me_hide),
        ) {
            when (state) {
                // Only while closing: the content shrinks away with nothing in it.
                LessonState.Closed -> Unit
                LessonState.Loading -> Row(
                    Modifier.heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.lab_teach_me_loading), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is LessonState.Failed -> Column(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(stringResource(state.reason.titleResource), style = MaterialTheme.typography.titleSmall)
                    val hint = state.message?.localizedText() ?: state.reason.hintResource?.let { stringResource(it) }
                    hint?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinguizeButton(stringResource(R.string.lab_teach_me_retry), controller::retry, ButtonKind.Tonal,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp))
                }
                is LessonState.Ready -> LessonView(state.lesson, state.quiz, controller)
            }
        }
    }
}

@Composable
private fun LessonView(lesson: Lesson, quiz: QuizState, controller: LessonController) {
    Column {
        Heading(stringResource(R.string.lab_teach_me_title))
        Body(lesson.rule, Modifier.padding(top = 4.dp))

        Label(stringResource(R.string.lab_teach_me_your_version))
        Body(lesson.yours)

        Label(stringResource(R.string.lab_teach_me_examples))
        lesson.examples.forEach { example ->
            Column(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) {}) {
                Text(example.sentence, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text(example.translation, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Label(stringResource(R.string.lab_teach_me_trap))
        Body(lesson.trap)

        Label(stringResource(R.string.lab_teach_me_quiz))
        quiz.questions.forEachIndexed { index, question ->
            Question(index, quiz.questions.size, question, quiz, onChoose = { controller.choose(index, it) })
        }
        if (quiz.finished) {
            Text(
                stringResource(R.string.lab_teach_me_score, quiz.score, quiz.questions.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (quiz.finished) LinguizeButton(stringResource(R.string.lab_teach_me_again), controller::again, ButtonKind.Text)
            LinguizeButton(stringResource(R.string.lab_teach_me_hide), controller::close, ButtonKind.Outlined)
        }
    }
}

@Composable
private fun Question(index: Int, count: Int, question: QuizQuestion, quiz: QuizState, onChoose: (Int) -> Unit) {
    val answered = quiz.answered(index)
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.lab_teach_me_question, index + 1, count),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(question.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        question.options.forEachIndexed { option, text ->
            val chosen = quiz.choice(index) == option
            val right = option == question.correct
            val mark = when {
                !answered -> null
                chosen && right -> stringResource(R.string.lab_teach_me_right)
                chosen -> stringResource(R.string.lab_teach_me_not_quite)
                right -> stringResource(R.string.lab_teach_me_right_answer)
                else -> null
            }
            val state = mark ?: if (answered) "" else stringResource(R.string.lab_teach_me_unanswered)
            Option(text, mark, chosen, right && answered, enabled = !answered, state = state) { onChoose(option) }
        }
        if (answered) {
            Text(question.feedback, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun Option(
    text: String,
    mark: String?,
    chosen: Boolean,
    highlight: Boolean,
    enabled: Boolean,
    state: String,
    onClick: () -> Unit,
) {
    val color = when {
        highlight -> MaterialTheme.colorScheme.onSurface
        chosen -> Linguize.colors.proof
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { if (state.isNotEmpty()) stateDescription = state }
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val glyph = when {
            highlight -> "✓"
            chosen -> "✗"
            else -> "○"
        }
        Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics {})
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color,
            fontWeight = if (highlight || chosen) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        mark?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = color,
                modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp).semantics { heading() })
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}
