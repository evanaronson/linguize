package com.evanaronson.linguize.ui.languagebridge

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.languagebridge.BridgeTargets
import com.evanaronson.linguize.core.languagebridge.Bridged
import com.evanaronson.linguize.core.languagebridge.Correspondence
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Loading
import com.evanaronson.linguize.ui.card.Outcome
import com.evanaronson.linguize.ui.card.PageHeader
import com.evanaronson.linguize.ui.card.ResultSurface
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.theme.Linguize

/** What the bridge page's buttons do; supplied by the host. */
class BridgeActions(
    val onBack: () -> Unit,
    val onTarget: (target: String) -> Unit,
    val onRetry: () -> Unit,
    val onOpenSettings: (() -> Unit)?,
    val onCopy: (text: String) -> Unit,
    /** Replaces the writer's text with the result; null when the host can't. */
    val onUse: ((text: String) -> Unit)?,
    /** Its button label: "Put back" in the popup, "Use this" in Try it. */
    val useLabel: Int,
    /** Runs the normal check on the result, in the target language. */
    val onCheck: (text: String, target: String) -> Unit,
    /** Said under the buttons when checking changes what the host puts back. */
    val checkNote: Int? = null,
)

/** A language's name in the UI language for Linguize's own languages, else its English name. */
@Composable
fun bridgeLanguageName(code: String): String = Language.forCode(code)?.displayName() ?: BridgeTargets.englishName(code)

/**
 * "Say it in Catalan": the message in the other language, the writer's own text, and a
 * word-by-word view of how their phrases carried over, with notes on false friends.
 */
@Composable
fun BridgeCard(
    state: BridgeState,
    targets: List<String>,
    actions: BridgeActions,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(onBack = actions.onBack)
    }
    val targetName = bridgeLanguageName(state.target)
    ResultSurface(modifier, scrollable, navigationKey = state.target) {
        PageHeader(stringResource(R.string.lab_language_bridge_say_it_in, targetName), actions.onBack)
        when (state) {
            is BridgeState.Loading -> Loading(state.text, stringResource(R.string.lab_language_bridge_saying, targetName))
            is BridgeState.Failed -> Failure(
                state.failure.reason, state.failure.userMessage, state.failure.detail, actions.onRetry, actions.onOpenSettings,
            )
            is BridgeState.Done -> {
                val result = state.result
                val others = BridgeTargets.others(targets, state.target, result.sourceLanguage, BridgeTargets::englishName)
                when (result.status) {
                    Bridged.Status.Ok -> Result(state, targetName, actions)
                    Bridged.Status.SameLanguage -> Outcome(
                        stringResource(R.string.lab_language_bridge_same_language, targetName),
                        if (others.isNotEmpty()) stringResource(R.string.lab_language_bridge_same_language_hint) else null,
                    )
                    Bridged.Status.Unclear -> Outcome(
                        stringResource(R.string.ai_could_not_understand),
                        stringResource(R.string.check_full_sentence),
                    )
                }
                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (code in others) {
                            LinguizeButton(
                                stringResource(R.string.lab_language_bridge_say_it_in, bridgeLanguageName(code)),
                                { actions.onTarget(code) },
                                ButtonKind.Text,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Result(state: BridgeState.Done, targetName: String, actions: BridgeActions) {
    val result = state.result
    Text(stringResource(R.string.lab_language_bridge_in_language, targetName), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    TextBlock(result.text)
    Spacer(Modifier.height(12.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.onUse?.let { use ->
            LinguizeButton(stringResource(actions.useLabel), { use(result.text) }, ButtonKind.Filled)
        }
        LinguizeButton(
            stringResource(R.string.lab_language_bridge_check),
            { actions.onCheck(result.text, state.target) },
            if (actions.onUse == null) ButtonKind.Filled else ButtonKind.Tonal,
        )
        LinguizeButton(stringResource(R.string.lab_language_bridge_copy), { actions.onCopy(result.text) }, ButtonKind.Text)
    }
    actions.checkNote?.let { Quiet(stringResource(it), Modifier.padding(top = 8.dp)) }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.lab_language_bridge_your_text), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    SelectionContainer {
        Text(state.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (result.pairs.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.lab_language_bridge_word_by_word), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        result.pairs.forEachIndexed { index, pair ->
            if (index > 0) HorizontalDivider(color = Linguize.colors.rowDivider)
            CorrespondenceRow(pair)
        }
    }
}

/** "your phrase → their phrase", the kind when it matters, and the note. The arrow is decoration. */
@Composable
private fun CorrespondenceRow(pair: Correspondence) {
    val kind = kindLabel(pair.kind)
    val spoken = listOfNotNull(
        stringResource(R.string.lab_language_bridge_becomes, pair.source, pair.target),
        kind,
        pair.note.ifEmpty { null },
    ).joinToString(". ")
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(vertical = 10.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pair.source, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("→", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pair.target, style = MaterialTheme.typography.titleSmall)
        }
        if (kind != null) {
            Text(
                kind,
                style = MaterialTheme.typography.labelMedium,
                // A false friend is a mark to notice, so it takes the one colour.
                color = if (pair.kind == Correspondence.Kind.FalseFriend) Linguize.colors.proof
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (pair.note.isNotEmpty()) {
            Text(pair.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Only the kinds worth flagging get a label; same and close words speak for themselves. */
@Composable
private fun kindLabel(kind: Correspondence.Kind): String? = when (kind) {
    Correspondence.Kind.Same, Correspondence.Kind.Close -> null
    Correspondence.Kind.Different -> stringResource(R.string.lab_language_bridge_kind_different)
    Correspondence.Kind.FalseFriend -> stringResource(R.string.lab_language_bridge_kind_false_friend)
}
