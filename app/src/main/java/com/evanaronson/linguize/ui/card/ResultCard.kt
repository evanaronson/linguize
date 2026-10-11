package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.llm.CheckFailure.Reason

/** The check result: loading, what was found, or why it failed. */
@Composable
fun ResultCard(
    state: CardState,
    actions: CardActions,
    modifier: Modifier = Modifier,
    /** Scroll inside the card; false when the card already sits in a scrolling screen. */
    scrollable: Boolean = true,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
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
                when (state) {
                    is CardState.Loading -> Loading(state.text)
                    is CardState.Failed -> Failure(state.reason, state.detail, actions)
                    is CardState.Done -> when (val result = state.result) {
                        is CheckResult.Reviewed -> ReviewContent(result, state.settled, actions)
                        CheckResult.Unclear ->
                            Outcome(
                                Mark.Unsure,
                                "The AI couldn't tell what this means",
                                "Check a full sentence in Catalan or Spanish",
                            )
                        is CheckResult.WrongLanguage ->
                            Outcome(
                                Mark.Unsure,
                                "This isn't ${result.expected}",
                                result.found?.let { "It looks like $it. Choose Linguize to detect the language." }
                                    ?: "Choose Linguize to detect the language.",
                            )
                    }
                }
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
private fun Loading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(14.dp))
    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Checking" })
}

/** What went wrong, in a few words. */
val Reason.title: String
    get() = when (this) {
        Reason.NoKey -> "No API key"
        Reason.BadKey -> "API key rejected"
        Reason.BadModel -> "Model not available"
        Reason.Offline -> "No connection"
        Reason.Timeout -> "The AI took too long"
        Reason.RateLimited -> "AI usage limit reached"
        Reason.Server -> "AI service unavailable"
        Reason.BadResponse -> "Unusable reply from the AI"
        Reason.TooLong -> "Text too long"
        Reason.TooMany -> "Too many checks in a minute"
    }

/** What to do about it, when there's something to say. */
private val Reason.hint: String?
    get() = when (this) {
        Reason.NoKey -> "Add one in settings"
        Reason.BadKey -> "Add a working key in settings"
        Reason.BadModel -> "Pick another model in settings"
        Reason.Offline -> "Connect to the internet, then retry"
        Reason.RateLimited -> "Wait a minute, then retry"
        Reason.TooLong -> "Select a shorter part, then check again"
        Reason.TooMany -> "Wait a minute, then select the text again"
        else -> null
    }

@Composable
private fun Failure(reason: Reason, providerMessage: String?, actions: CardActions) {
    Outcome(Mark.Problem, reason.title, reason.hint)
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
            actions.onOpenSettings?.let { FilledTonalButton(onClick = it) { Text("Open settings") } }
        // Nothing to retry: a limited card never started a check.
        Reason.TooLong, Reason.TooMany -> Unit
        else -> FilledTonalButton(onClick = actions.onRetry) { Text("Retry") }
    }
}
