package com.evanaronson.linguize.ui.card

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.readCopiedText
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * Lab, Replying to (docs/lab/reply-context.md): on an unclear check from another app, the
 * writer can add the message they are answering and check again with it as context.
 * Only hosts that check text from another app supply these; the chat sends its context
 * automatically and Notes has none.
 *
 * Nothing is read until the writer taps, and nothing read is kept beyond the card.
 */
class ReplyContextActions(
    /**
     * Reads the messages visible in the app behind the card, oldest first, at this moment;
     * empty when none is readable. Null when the card can't read that app (no accessibility
     * service, or a host the service can't see behind): then only Paste is offered.
     */
    val pick: (() -> List<String>)?,
    /** Reads the clipboard now (the card is in front); null when it has no text. */
    val paste: () -> String?,
    /** Checks the same text again as a reply to the message. */
    val checkAgain: (message: String) -> Unit,
) {
    companion object {
        /** The card's view model checks again; [pick] is the host's screen reader, if any. */
        fun of(check: CheckViewModel, context: Context, pick: (() -> List<String>)? = null) = ReplyContextActions(
            pick = pick,
            paste = { (context.readCopiedText() as? ClipboardText.Text)?.value },
            checkAgain = check::checkReplyingTo,
        )

        /** How many of the latest visible messages the picker offers. */
        const val PICK_LIMIT = 4

        /** A message as it is offered and sent: trimmed, a long one keeps its end (as in the chat). */
        fun message(text: String?): String? = ReplyPrompt.normalize(text?.trim()?.takeLast(ReplyPrompt.MAX_CHARS))
    }
}

/** Where the writer is in adding the message they reply to. */
sealed interface ReplyStep {
    /** The two ways to add one. */
    data object Choose : ReplyStep

    /** Messages read from the screen, oldest first, to tap one. */
    data class Picking(val messages: List<String>) : ReplyStep

    /** The screen had nothing readable. */
    data object NoneFound : ReplyStep

    /** The clipboard had no text. */
    data object EmptyClipboard : ReplyStep

    /** The message chosen, waiting for Check again. */
    data class Chosen(val message: String) : ReplyStep
}

/**
 * The section's state, kept only while the card shows it (not saved: what was read from the
 * screen or the clipboard never outlives the card).
 */
@Stable
class ReplyPickState(initial: ReplyStep = ReplyStep.Choose) {
    var step by mutableStateOf(initial)
        private set

    /** Reads the screen behind the card now. */
    fun pick(actions: ReplyContextActions) {
        val read = actions.pick ?: return
        val messages = read().mapNotNull(ReplyContextActions::message).takeLast(ReplyContextActions.PICK_LIMIT)
        step = if (messages.isEmpty()) ReplyStep.NoneFound else ReplyStep.Picking(messages)
    }

    /** Reads the clipboard now. */
    fun paste(actions: ReplyContextActions) {
        step = ReplyContextActions.message(actions.paste())?.let(ReplyStep::Chosen) ?: ReplyStep.EmptyClipboard
    }

    fun choose(message: String) {
        step = ReplyContextActions.message(message)?.let(ReplyStep::Chosen) ?: ReplyStep.Choose
    }

    fun remove() {
        step = ReplyStep.Choose
    }

    /** Checks again with the chosen message; the card's next state replaces this section. */
    fun checkAgain(actions: ReplyContextActions) {
        val chosen = step as? ReplyStep.Chosen ?: return
        actions.checkAgain(chosen.message)
    }
}

/** "What are you replying to?": Pick their message and Paste, the picker, then the preview and Check again. */
@Composable
internal fun ReplyingToSection(
    actions: ReplyContextActions,
    state: ReplyPickState = remember { ReplyPickState() },
) {
    Column(Modifier.fillMaxWidth()) {
        when (val step = state.step) {
            is ReplyStep.Chosen -> {
                Text(stringResource(R.string.replying_to), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(InsetShape)
                        .background(Linguize.colors.panel)
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.replying_to_quote, step.message),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    RemoveButton(onClick = state::remove)
                }
                Spacer(Modifier.height(12.dp))
                LinguizeButton(stringResource(R.string.check_again), { state.checkAgain(actions) }, ButtonKind.Filled)
            }
            is ReplyStep.Picking -> {
                Text(stringResource(R.string.replying_to_tap_their_message), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                val choose = stringResource(R.string.replying_to_choose_message)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    step.messages.forEach { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(InsetShape)
                                .background(Linguize.colors.panel)
                                .clickable(onClickLabel = choose) { state.choose(message) }
                                .heightIn(min = 48.dp)
                                .padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ChooseButtons(actions, state)
            }
            ReplyStep.Choose, ReplyStep.NoneFound, ReplyStep.EmptyClipboard -> {
                Text(stringResource(R.string.replying_to_what), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                ChooseButtons(actions, state)
                val message = when (step) {
                    ReplyStep.NoneFound -> R.string.replying_to_no_messages
                    ReplyStep.EmptyClipboard -> R.string.replying_to_empty_clipboard
                    else -> null
                }
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(message), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChooseButtons(actions: ReplyContextActions, state: ReplyPickState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.pick != null) {
            LinguizeButton(stringResource(R.string.replying_to_pick), { state.pick(actions) }, ButtonKind.Tonal)
        }
        LinguizeButton(stringResource(R.string.replying_to_paste), { state.paste(actions) }, ButtonKind.Tonal)
    }
}

/** The preview's ×, heard as "Remove". */
@Composable
private fun RemoveButton(onClick: () -> Unit) {
    val label = stringResource(R.string.replying_to_remove)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("×", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {})
    }
}
