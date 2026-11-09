package com.evanaronson.linguize.ui.card

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckFailure.Reason
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.replycontext.ReplyContextFooter
import com.evanaronson.linguize.ui.replycontext.ReplyContextHeader
import com.evanaronson.linguize.ui.secondopinion.WithSecondOpinion
import com.evanaronson.linguize.ui.text.hintResource
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.phrasebook.ProvidePhraseOrigin
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize
import kotlinx.coroutines.CompletableDeferred

/**
 * The check result: loading, what was found, or why it failed. A reviewed result has two
 * pages: the suggestions, with Meaning as the card's footer row, and Meaning itself, which
 * slides in from the end and back.
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
    // How the text was read is a page of its own, kept until the result changes.
    var showingUnderstanding by rememberSaveable(reviewed, settled) { mutableStateOf(false) }
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = showingUnderstanding) { showingUnderstanding = false }
    }
    // Lab: How it lands is a page of its own for the accepted text.
    var showingLands by rememberSaveable(reviewed, settled) { mutableStateOf(false) }
    if (com.evanaronson.linguize.ui.tonecheck.showLandsPage(actions.howItLands, reviewed?.revision?.workingText,
            showingLands, { showingLands = it }, modifier, scrollable)) return
    ResultSurface(
        modifier,
        scrollable,
        navigationKey = showingUnderstanding,
        footer = if ((reviewed != null && !showingUnderstanding) || onUnderstandCopiedText != null) {
            {
                if (reviewed != null && !showingUnderstanding) {
                    UnderstandingEntry(onOpen = { showingUnderstanding = true })
                    actions.howItLands?.let { com.evanaronson.linguize.ui.tonecheck.LandsEntry(it) { showingLands = true } }
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
        ReplyContextHeader(actions.replyContext)
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
                    ReviewPages(result, state, actions, showingUnderstanding) { showingUnderstanding = it }
                }
                CheckResult.Unclear -> {
                    Outcome(
                        stringResource(R.string.ai_could_not_understand),
                        stringResource(R.string.check_full_sentence),
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
                            linkText = stringResource(R.string.say_what_you_meant),
                            editorTitle = stringResource(R.string.meaning),
                            submitKind = if (state.retainedRevision?.canUndo == true && actions.review != null)
                                ButtonKind.Tonal else ButtonKind.Filled)
                    }
                    RetainedChanges(state.retainedRevision, actions.review, canApply = true)
                }
                is CheckResult.WrongLanguage -> {
                    Outcome(
                        stringResource(R.string.wrong_language, languageDisplayName(result.expected)),
                        result.found?.let { stringResource(R.string.wrong_language_detected, languageDisplayName(it)) }
                            ?: stringResource(R.string.choose_auto_language),
                    )
                    RetainedChanges(state.retainedRevision, actions.review, canApply = true)
                }
            }
        }
        ReplyContextFooter(actions.replyContext, state, hidden = showingUnderstanding)
    }
}

@Composable
private fun ReviewPages(
    result: CheckResult.Reviewed,
    state: CardState.Done,
    actions: CardActions,
    showingUnderstanding: Boolean,
    onShowUnderstanding: (Boolean) -> Unit,
) {
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    AnimatedContent(
        targetState = showingUnderstanding,
        transitionSpec = {
            val direction = if (targetState) forward else -forward
            (slideInHorizontally { direction * it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { -direction * it / 3 } + fadeOut())
        },
        label = "understanding",
    ) { understandingPage ->
        Column {
            if (understandingPage) {
                UnderstandingPage(
                    result.meaning,
                    result.assumptions,
                    state.settled,
                    actions.onSettle,
                    actions.onRemoveChoice,
                    onBack = { onShowUnderstanding(false) },
                    onClarify = actions.onClarify,
                    onRemoveClarification = actions.onRemoveClarification,
                )
            } else {
                WithSecondOpinion(actions.secondOpinion) { Suggestions(result, state.settled, actions) }
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
    /** A page change reveals the card header in both its own and any parent scroll container. */
    navigationKey: Any? = null,
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
        val top = remember { BringIntoViewRequester() }
        val placed = remember(navigationKey) { CompletableDeferred<IntSize>() }
        var revealedKey by remember { mutableStateOf(navigationKey) }
        LaunchedEffect(navigationKey) {
            if (revealedKey != navigationKey) {
                revealedKey = navigationKey
                // Wait for actual content placement before resolving its position in parent scrolls.
                val size = placed.await()
                scroll.scrollTo(0)
                // Navigation starts below the card's top. Reveal that top edge without asking
                // the parent to fit the entire (potentially taller than the viewport) page.
                top.bringIntoView(Rect(0f, 0f, size.width.toFloat(), 0f))
            }
        }
        Box {
            Column(Modifier.then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)) {
                Column(
                    Modifier
                        // Relocation must start from a nonempty layout: Compose maps empty
                        // layout bounds to Rect.Zero, losing the offset into parent scrolls.
                        .bringIntoViewRequester(top)
                        .onGloballyPositioned { placed.complete(it.size) }
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
