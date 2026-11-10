package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.text.hintResource
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.phrasebook.ProvidePhraseOrigin
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * The check result: loading, what was found, or why it failed. A reviewed result is one
 * surface: the suggestions, ending in the final Replace or Send, then Meaning (what the text
 * says and how it lands), which opens in place at the card's foot.
 */
@Composable
fun ResultCard(
    state: CardState,
    actions: CardActions,
    modifier: Modifier = Modifier,
    /** Scroll inside the card; false when the card already sits in a scrolling screen. */
    scrollable: Boolean = true,
    /** Starts the separate clipboard job when this host supports it. */
    onUnderstandCopiedText: (() -> Unit)? = null,
) {
    val done = state as? CardState.Done
    val reviewed = done?.result as? CheckResult.Reviewed
    val settled = done?.settled.orEmpty()
    // Lab, Meaning: the one section that opens in place at the card's foot. Kept here, not in
    // the section, so a clarification sent from inside it (the card checks again) reopens on
    // the new answer; a different text starts closed. No page, so no Back level of its own.
    val checked = when (state) {
        is CardState.Loading -> state.text
        is CardState.Done -> state.sourceText ?: reviewed?.revision?.original
        is CardState.Failed -> null
    }
    var meaningOpen by rememberSaveable { mutableStateOf(false) }
    var meaningOpenFor by rememberSaveable { mutableStateOf(checked) }
    if (checked != null && checked != meaningOpenFor) {
        meaningOpenFor = checked
        meaningOpen = false
    }
    ResultSurface(
        modifier,
        scrollable,
        footer = if (reviewed != null || onUnderstandCopiedText != null) {
            {
                if (reviewed != null) {
                    MeaningFooter(reviewed, settled, actions, meaningOpen) { meaningOpen = it }
                }
                onUnderstandCopiedText?.let { open ->
                    FooterRow(
                        stringResource(R.string.understand_copied_text),
                        stringResource(R.string.understand_copied_text_subtitle),
                        onClick = open,
                    )
                }
            }
        } else null,
    ) {
        when (state) {
            is CardState.Loading -> {
                if (state.retainedRevision?.canUndo == true) {
                    Progress(stringResource(R.string.checking))
                    RetainedChanges(state.retainedRevision, actions.review)
                } else {
                    Loading(state.text)
                }
            }
            is CardState.Failed -> {
                Failure(state.reason, state.userMessage, state.detail, actions.onRetry, actions.onOpenSettings)
                RetainedChanges(state.retainedRevision, actions.review)
            }
            is CardState.Done -> when (val result = state.result) {
                is CheckResult.Reviewed -> ProvidePhraseOrigin(actions.phraseOrigin) {
                    Suggestions(result, state.settled, actions)
                }
                CheckResult.Unclear -> {
                    Outcome(
                        stringResource(R.string.ai_could_not_understand),
                        stringResource(if (actions.onClarify != null) R.string.unclear_hint else R.string.unclear_hint_read_only),
                    )
                    state.sourceText?.let { text ->
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.original_text), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        TextBlock(text)
                    }
                    if (actions.onClarify != null || state.settled.any { it.isMeaning }) {
                        Spacer(Modifier.height(8.dp))
                        MeaningSection("", state.settled, actions.onClarify, actions.onRemoveClarification,
                            editorTitle = stringResource(R.string.meaning),
                            submitKind = if (state.retainedRevision?.canUndo == true && actions.review != null)
                                ButtonKind.Tonal else ButtonKind.Filled)
                    }
                    RetainedChanges(state.retainedRevision, actions.review, canApply = true)
                }
                is CheckResult.WrongLanguage -> {
                    Outcome(
                        stringResource(R.string.wrong_language, languageDisplayName(result.expected)),
                        wrongLanguageHint(LocalLanguageHint.current, result.found?.let { languageDisplayName(it) }),
                    )
                    RetainedChanges(state.retainedRevision, actions.review, canApply = true)
                }
            }
        }
    }
}

/**
 * The card itself: paper with a hairline, content padded inside, and an optional [footer]
 * that runs edge to edge at the bottom. Long content scrolls, with the bottom edge faded
 * while there is more.
 */
@Composable
internal fun ResultSurface(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = Linguize.colors.popup
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // Floating over another app it casts a shadow; inside a screen it sits flat.
        shadowElevation = if (scrollable) 8.dp else 0.dp,
    ) {
        val scroll = rememberScrollState()
        Box {
            Column(Modifier.then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)) {
                Column(
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = if (footer != null) 14.dp else 18.dp),
                    content = content,
                )
                footer?.invoke()
            }
            // Fades out the bottom edge while there's more to scroll to.
            if (scrollable && scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Brush.verticalGradient(listOf(container.copy(alpha = 0f), container))),
                )
            }
        }
    }
}

/** An ink bar on a light track. */
@Composable
internal fun Progress(label: String) {
    LinearProgressIndicator(
        Modifier.fillMaxWidth().semantics { contentDescription = label },
        color = MaterialTheme.colorScheme.onSurface,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
    )
}

@Composable
internal fun Loading(text: String, label: String = stringResource(R.string.checking)) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(14.dp))
    Progress(label)
}

@Composable
internal fun Failure(reason: Reason, userMessage: CheckFailure.Message?, providerMessage: String?, onRetry: () -> Unit, onOpenSettings: (() -> Unit)?) {
    Outcome(stringResource(reason.titleResource), reason.hintResource?.let { stringResource(it) })
    userMessage?.let { message ->
        Spacer(Modifier.height(8.dp))
        Text(message.localizedText(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (providerMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            providerMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when (reason) {
        Reason.NoKey, Reason.BadKey, Reason.BadModel -> onOpenSettings?.let {
            Spacer(Modifier.height(12.dp))
            LinguizeButton(stringResource(R.string.open_settings), it, ButtonKind.Filled)
        }
        // Nothing to retry: a limited card never started a check.
        Reason.TooLong, Reason.TooMany -> Unit
        else -> {
            Spacer(Modifier.height(12.dp))
            LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Filled)
        }
    }
}
