package com.evanaronson.linguize.ui.reply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.card.UnderstandCard
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.readCopiedText
import com.evanaronson.linguize.ui.understand.UnderstandState
import com.evanaronson.linguize.ui.understand.UnderstandViewModel

/**
 * Lab: Help me reply in the app. Paste a message, read what it says, then answer it. The same
 * understand card and reply page as the accessibility popup; nothing is saved.
 */
@Composable
fun ReplyScreen(onOpenSettings: () -> Unit) {
    val app = App.of(LocalContext.current)
    val understand: UnderstandViewModel = viewModel(key = "lab_reply_understand", factory = UnderstandViewModel.factory(app))
    val reply: ReplyViewModel = viewModel(key = "lab_reply_drafter", factory = ReplyViewModel.factory(app))
    ReplyScreenContent(understand, reply, onOpenSettings)
}

@Composable
internal fun ReplyScreenContent(understand: UnderstandViewModel, reply: ReplyViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var message by rememberSaveable { mutableStateOf("") }
    var blank by rememberSaveable { mutableStateOf(false) }
    var replying by rememberSaveable { mutableStateOf(false) }

    fun understandMessage() {
        val text = message.trim()
        blank = text.isEmpty()
        if (blank) return
        replying = false
        understand.captured(ClipboardText.Text(text))
    }

    ScreenColumn {
        SectionCard(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            LinguizeTextField(
                value = message,
                onValueChange = {
                    message = it
                    blank = false
                    // What is shown below was for the old message.
                    replying = false
                    understand.readingClipboard()
                },
                label = stringResource(R.string.lab_reply_drafter_message_label),
                placeholder = stringResource(R.string.lab_reply_drafter_message_placeholder),
                supporting = if (blank) stringResource(R.string.lab_reply_drafter_no_message) else null,
                isError = blank,
                large = true,
                minLines = 3,
            )
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinguizeButton(stringResource(R.string.lab_reply_drafter_paste), {
                    (context.readCopiedText() as? ClipboardText.Text)?.let {
                        message = it.value
                        blank = false
                        replying = false
                        understand.readingClipboard()
                    }
                }, ButtonKind.Outlined)
                LinguizeButton(stringResource(R.string.lab_reply_drafter_understand), ::understandMessage, ButtonKind.Filled)
            }
            val state = understand.state
            val cardModifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            if (replying && state is UnderstandState.Done) {
                ReplyCard(reply, onBack = { replying = false }, cardModifier, scrollable = false, onOpenSettings = onOpenSettings)
            } else if (state !is UnderstandState.ReadingClipboard && state !is UnderstandState.Message) {
                UnderstandCard(
                    state,
                    onRetry = understand::retry,
                    modifier = cardModifier,
                    onOpenSettings = onOpenSettings,
                    onHelpReply = (state as? UnderstandState.Done)?.let { done ->
                        {
                            reply.start(done.text, done.result.translation)
                            replying = true
                        }
                    },
                    scrollable = false,
                )
            }
            Quiet(stringResource(R.string.lab_reply_drafter_page_note), Modifier.padding(top = 12.dp))
        }
    }
}
