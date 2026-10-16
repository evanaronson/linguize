package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.ui.text.hintResource
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource

/** The check result: loading, what was found, or why it failed. */
@Composable
fun ResultCard(
    state: CardState,
    actions: CardActions,
    modifier: Modifier = Modifier,
    /** Scroll inside the card; false when the card already sits in a scrolling screen. */
    scrollable: Boolean = true,
    leadingContent: @Composable ColumnScope.() -> Unit = {},
) {
    ResultSurface(modifier, scrollable) {
        leadingContent()
        when (state) {
            is CardState.Loading -> {
                if (state.retainedRevision?.canUndo == true) {
                    val label = stringResource(R.string.checking)
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
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
                is CheckResult.Reviewed -> ReviewContent(result, state.settled, actions)
                CheckResult.Unclear ->
                    Outcome(
                        Mark.Unsure,
                        stringResource(R.string.ai_could_not_understand),
                        stringResource(R.string.check_full_sentence),
                    )
                is CheckResult.WrongLanguage ->
                    Outcome(
                        Mark.Unsure,
                        stringResource(R.string.wrong_language, languageDisplayName(result.expected)),
                        result.found?.let { stringResource(R.string.wrong_language_detected, languageDisplayName(it)) }
                            ?: stringResource(R.string.choose_auto_language),
                    )
            }
        }
    }
}

/** Accepted text stays inspectable and undoable without offering the old suggestions. */
@Composable
private fun RetainedChanges(revision: Revision?, review: ReviewActions?) {
    if (revision == null || !revision.canUndo) return
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val count = revision.acceptedCount
        Text(
            pluralStringResource(R.plurals.changes_accepted_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        review?.let { TextButton(onClick = it.onUndo) { Text(stringResource(R.string.undo)) } }
    }
    Text(revision.workingText, style = MaterialTheme.typography.bodyLarge)
}

/** The same surface and scrolling for correction and reading jobs. */
@Composable
internal fun ResultSurface(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        val scroll = rememberScrollState()
        Box {
            Column(
                Modifier
                    .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                content()

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
    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
}

@Composable
internal fun Failure(reason: Reason, userMessage: CheckFailure.Message?, providerMessage: String?, onRetry: () -> Unit, onOpenSettings: (() -> Unit)?) {
    Outcome(Mark.Problem, stringResource(reason.titleResource), reason.hintResource?.let { stringResource(it) })
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
    Spacer(Modifier.height(12.dp))
    when (reason) {
        Reason.NoKey, Reason.BadKey, Reason.BadModel ->
            onOpenSettings?.let { FilledTonalButton(onClick = it) { Text(stringResource(R.string.open_settings)) } }
        // Nothing to retry: a limited card never started a check.
        Reason.TooLong, Reason.TooMany -> Unit
        else -> FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
