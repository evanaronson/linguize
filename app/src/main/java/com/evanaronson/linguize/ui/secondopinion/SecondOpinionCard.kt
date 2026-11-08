package com.evanaronson.linguize.ui.secondopinion

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.opinion.Agreement
import com.evanaronson.linguize.core.opinion.OpinionComparison
import com.evanaronson.linguize.core.opinion.OpinionItem
import com.evanaronson.linguize.core.opinion.SecondAnswer
import com.evanaronson.linguize.data.SecondOpinionTarget
import com.evanaronson.linguize.ui.card.Progress
import com.evanaronson.linguize.ui.card.SectionDivider
import com.evanaronson.linguize.ui.card.SuccessLine
import com.evanaronson.linguize.ui.card.visibleValue
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.text.hintResource
import com.evanaronson.linguize.ui.text.labelResource
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize

/** The second opinion for the check on the card, so each change's explanation can show its badge. */
private val LocalSecondOpinion = compositionLocalOf<SecondOpinionState?> { null }

/**
 * The suggestions page ([content]) with a second opinion: badges in the explanations of its
 * changes, and the second-opinion section after it. Without a [controller], just [content].
 */
@Composable
internal fun WithSecondOpinion(controller: SecondOpinionController?, content: @Composable () -> Unit) {
    if (controller == null) {
        content()
        return
    }
    val key = controller.requestKey
    LaunchedEffect(key) { controller.prepare() }
    val state = controller.state
    CompositionLocalProvider(LocalSecondOpinion provides state) {
        content()
        SecondOpinionSection(state, controller::ask)
    }
}

/** The badge for the first answer's edit [editId], for its explanation; null when there's no second opinion. */
@Composable
internal fun secondOpinionBadge(editId: Int): (@Composable () -> Unit)? {
    val answered = LocalSecondOpinion.current as? SecondOpinionState.Answered ?: return null
    val comparison = (answered.answer as? SecondAnswer.Compared)?.comparison ?: return null
    val item = comparison.forFirstEdit(editId) ?: return null
    return {
        val (first, second) = names(answered.checkedWith, answered.target)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AgreementBadge(item.agreement, first, second)
            val shown = item.shown
            val detail = when (item.agreement) {
                Agreement.OnlyFirst -> stringResource(R.string.lab_second_opinion_keeps, second)
                Agreement.Disagree -> says(second, shown.from, shown.second.orEmpty())
                Agreement.Agree, Agreement.OnlySecond -> null
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SecondOpinionSection(state: SecondOpinionState?, onAsk: () -> Unit) {
    if (state == null || state is SecondOpinionState.Unavailable) return
    SectionDivider()
    Text(
        stringResource(R.string.lab_second_opinion_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(6.dp))
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state) {
            is SecondOpinionState.Ready -> {
                val (_, second) = names(state.checkedWith, state.target)
                Text(stringResource(R.string.lab_second_opinion_caption, second),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinguizeButton(stringResource(R.string.lab_second_opinion_ask, second), onAsk, ButtonKind.Outlined)
            }
            is SecondOpinionState.Asking -> {
                val (_, second) = names(state.checkedWith, state.target)
                Spacer(Modifier.height(4.dp))
                Progress(stringResource(R.string.lab_second_opinion_asking, second))
            }
            is SecondOpinionState.Failed -> {
                Text(stringResource(R.string.lab_second_opinion_failed, stringResource(state.reason.titleResource)),
                    style = MaterialTheme.typography.titleSmall)
                state.reason.hintResource?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.userMessage?.let {
                    Text(it.localizedText(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LinguizeButton(stringResource(R.string.retry), onAsk, ButtonKind.Text)
            }
            is SecondOpinionState.Answered -> {
                val (first, second) = names(state.checkedWith, state.target)
                when (val answer = state.answer) {
                    is SecondAnswer.Compared -> Summary(answer.comparison, first, second)
                    SecondAnswer.Unclear -> Text(stringResource(R.string.lab_second_opinion_unclear, second),
                        style = MaterialTheme.typography.titleSmall)
                    is SecondAnswer.WrongLanguage -> Text(
                        answer.found?.let { stringResource(R.string.lab_second_opinion_wrong_language, second, languageDisplayName(it)) }
                            ?: stringResource(R.string.lab_second_opinion_wrong_language_unknown, second),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            is SecondOpinionState.Unavailable -> Unit
        }
    }
}

/** The headline, the counts, then each change with its badge. */
@Composable
private fun Summary(comparison: OpinionComparison, first: String, second: String) {
    when {
        comparison.bothLookGood -> SuccessLine(stringResource(R.string.lab_second_opinion_both_fine, first, second))
        comparison.allAgree && !comparison.incomplete ->
            SuccessLine(stringResource(R.string.lab_second_opinion_all_agree, first, second))
        else -> Text(stringResource(R.string.lab_second_opinion_differ, first, second), style = MaterialTheme.typography.titleSmall)
    }
    if (comparison.items.isNotEmpty()) {
        val counts = listOfNotNull(
            comparison.count(Agreement.Agree).takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.lab_second_opinion_agreed_count, it, it)
            },
            comparison.count(Agreement.OnlyFirst).takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.lab_second_opinion_only_count, it, it, first)
            },
            comparison.count(Agreement.OnlySecond).takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.lab_second_opinion_only_count, it, it, second)
            },
            comparison.count(Agreement.Disagree).takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.lab_second_opinion_disagreed_count, it, it)
            },
        ).joinToString(stringResource(R.string.list_separator))
        Text(counts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (comparison.incomplete) {
        Text(stringResource(R.string.lab_second_opinion_incomplete), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
    comparison.items.forEach { item -> ItemRow(item, first, second) }
}

@Composable
private fun ItemRow(item: OpinionItem, first: String, second: String) {
    val shown = item.shown
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AgreementBadge(item.agreement, first, second)
        when (item.agreement) {
            Agreement.Agree, Agreement.OnlyFirst -> ChangeText(describe(shown.from, shown.first.orEmpty()))
            Agreement.OnlySecond -> ChangeText(describe(shown.from, shown.second.orEmpty()))
            Agreement.Disagree -> {
                ChangeText(says(first, shown.from, shown.first.orEmpty()))
                ChangeText(says(second, shown.from, shown.second.orEmpty()))
            }
        }
    }
}

@Composable
private fun ChangeText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** "Both agree", "Only Gemini" or "Disagree", outlined; the screen reader hears it as a second opinion. */
@Composable
private fun AgreementBadge(agreement: Agreement, first: String, second: String) {
    val label = when (agreement) {
        Agreement.Agree -> stringResource(R.string.lab_second_opinion_badge_agree)
        Agreement.OnlyFirst -> stringResource(R.string.lab_second_opinion_badge_only, first)
        Agreement.OnlySecond -> stringResource(R.string.lab_second_opinion_badge_only, second)
        Agreement.Disagree -> stringResource(R.string.lab_second_opinion_badge_disagree)
    }
    val color: Color = when (agreement) {
        Agreement.Agree -> MaterialTheme.colorScheme.onSurface
        Agreement.OnlyFirst, Agreement.OnlySecond -> MaterialTheme.colorScheme.onSurfaceVariant
        Agreement.Disagree -> Linguize.colors.proof
    }
    val spoken = stringResource(R.string.lab_second_opinion_badge_description, label)
    Row(
        Modifier
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (agreement == Agreement.Agree) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** "cami becomes camí", "Add ," or "Remove que", as the card writes changes. */
@Composable
private fun describe(from: String, to: String): String = when {
    from.isEmpty() -> stringResource(R.string.change_add, to.visibleValue())
    to.isEmpty() -> stringResource(R.string.change_remove, from.visibleValue())
    else -> stringResource(R.string.change_becomes, from.visibleValue(), to.visibleValue())
}

/** "OpenAI says camino", or "OpenAI removes que" when its version is empty. */
@Composable
private fun says(name: String, from: String, to: String): String =
    if (to.isEmpty()) stringResource(R.string.lab_second_opinion_says_remove, name, from.visibleValue())
    else stringResource(R.string.lab_second_opinion_says, name, to.visibleValue())

/**
 * What to call the two answers: the services when they differ ("Gemini", "OpenAI"), else the
 * exact models, which are then the only difference.
 */
@Composable
private fun names(checkedWith: SecondOpinionTarget, target: SecondOpinionTarget): Pair<String, String> =
    if (checkedWith.provider != target.provider) {
        stringResource(checkedWith.provider.labelResource) to stringResource(target.provider.labelResource)
    } else {
        checkedWith.model to target.model
    }
