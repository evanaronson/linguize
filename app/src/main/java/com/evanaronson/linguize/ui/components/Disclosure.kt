package com.evanaronson.linguize.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import kotlinx.coroutines.delay

/*
 * The app's one expand-in-place pattern (docs/lab/expand-pattern.md): a header row that stays
 * put, a chevron that points down when closed and up when open, and content that grows in
 * below the header. A chevron pointing to the end (›) still means "go somewhere": a page,
 * or an editor that takes over the row.
 */

/** Timing shared by everything that opens in place, so the card's sections move alike. */
object DisclosureMotion {
    const val EXPAND_MILLIS = 220
    const val COLLAPSE_MILLIS = 160
}

/**
 * Remembers whether a disclosure is open, across recomposition, rotation and process death,
 * starting closed. Pass the [keys] that identify what it discloses (a row id, a result) so a
 * different item starts closed again.
 */
@Composable
fun rememberDisclosureState(vararg keys: Any?, initiallyExpanded: Boolean = false): MutableState<Boolean> =
    rememberSaveable(*keys) { mutableStateOf(initiallyExpanded) }

/**
 * Accessibility for anything that opens and closes in place: one click action named for what
 * it does ("Show details"), the state read as "Expanded" or "Collapsed", and the platform's
 * expand and collapse actions. Use it on the header of a [Disclosure], or on a compact
 * trigger whose content is laid out elsewhere (a chip under a chat bubble).
 */
@Composable
fun Modifier.disclosureToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    expandLabel: String? = null,
    collapseLabel: String? = null,
): Modifier {
    val show = expandLabel ?: stringResource(R.string.lab_expand_pattern_show)
    val hide = collapseLabel ?: stringResource(R.string.lab_expand_pattern_hide)
    val state = stringResource(if (expanded) R.string.lab_expand_pattern_expanded else R.string.lab_expand_pattern_collapsed)
    return this
        .clickable(onClickLabel = if (expanded) hide else show) { onExpandedChange(!expanded) }
        .semantics {
            stateDescription = state
            if (expanded) collapse(hide) { onExpandedChange(false); true }
            else expand(show) { onExpandedChange(true); true }
        }
}

/**
 * The chevron of something that opens in place: the end-pointing [Chevron] turned to point
 * down when closed and up when open, in either layout direction. Silent to screen readers;
 * the state is on the toggle.
 */
@Composable
fun DisclosureChevron(expanded: Boolean, modifier: Modifier = Modifier, size: Int = 22) {
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val angle by animateFloatAsState(
        targetValue = turn * if (expanded) -90f else 90f,
        animationSpec = tween(DisclosureMotion.EXPAND_MILLIS, easing = FastOutSlowInEasing),
        label = "chevron",
    )
    Chevron(modifier.rotate(angle), size)
}

/**
 * A header row that opens [content] in place below it. The header stays where it is, so what
 * it summarises stays in view; the whole row is the target, at least 48dp tall. Opening it
 * scrolls the surrounding card or screen just enough to show the content (never on restore).
 *
 * State is hoisted: use [rememberDisclosureState] for the usual saveable state, or keep it in
 * a view model when only one item may be open at a time.
 */
@Composable
fun Disclosure(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    header: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** Read with the click action; defaults to "Show details" and "Hide details". */
    expandLabel: String? = null,
    collapseLabel: String? = null,
    headerPadding: PaddingValues = PaddingValues(vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    // Only a change after the first composition is the writer's tap; a restored open state stays put.
    var composed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (composed && expanded) {
            delay(DisclosureMotion.EXPAND_MILLIS.toLong())
            requester.bringIntoView()
        }
        composed = true
    }
    Column(modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        Row(
            Modifier
                .fillMaxWidth()
                .disclosureToggle(expanded, onExpandedChange, expandLabel, collapseLabel)
                .heightIn(min = 48.dp)
                .padding(headerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { header() }
            DisclosureChevron(expanded)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(DisclosureMotion.EXPAND_MILLIS, easing = FastOutSlowInEasing)) +
                fadeIn(tween(DisclosureMotion.EXPAND_MILLIS)),
            exit = shrinkVertically(tween(DisclosureMotion.COLLAPSE_MILLIS, easing = FastOutSlowInEasing)) +
                fadeOut(tween(DisclosureMotion.COLLAPSE_MILLIS)),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** A [Disclosure] whose header is a title over an optional muted [summary], with its own saveable state. */
@Composable
fun Disclosure(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    /** What identifies the disclosed item; a new key starts closed. */
    key: Any? = null,
    expandLabel: String? = null,
    collapseLabel: String? = null,
    headerPadding: PaddingValues = PaddingValues(vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberDisclosureState(key)
    Disclosure(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        header = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!summary.isNullOrEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        modifier = modifier,
        expandLabel = expandLabel,
        collapseLabel = collapseLabel,
        headerPadding = headerPadding,
        content = content,
    )
}
