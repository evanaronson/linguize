package com.evanaronson.linguize.ui.languagebridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.languagebridge.BridgeTargets
import com.evanaronson.linguize.core.languagebridge.LanguageGuess
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.copyToClipboard

/** The bridge view model of the screen hosting it. */
@Composable
fun rememberBridgeViewModel(): BridgeViewModel =
    viewModel(factory = BridgeViewModel.factory(App.of(LocalContext.current)))

/**
 * Where [text] would go by default: the first profile language that isn't the one the
 * writer [pinned], or, without one, the one the text looks like it's in.
 */
fun defaultBridgeTarget(text: String, pinned: String?, targets: List<String>): String? =
    BridgeTargets.defaultTarget(targets.ifEmpty { Language.all.map { it.code } }, pinned ?: LanguageGuess.guess(text))

/** The text a check card would bridge: its accepted changes, else what was checked. */
fun bridgeTextOf(check: CheckViewModel): String? =
    check.workingText ?: (check.state as? CardState.Done)?.sourceText

/** The check card's footer row: "Say it in Catalan ›". Nothing when there's nowhere to go. */
@Composable
fun BridgeFooterEntry(bridge: BridgeViewModel, check: CheckViewModel, pinned: Language?) {
    val text = bridgeTextOf(check) ?: return
    val target = defaultBridgeTarget(text, pinned?.code, bridge.targets) ?: return
    FooterRow(
        stringResource(R.string.lab_language_bridge_say_it_in, bridgeLanguageName(target)),
        stringResource(R.string.lab_language_bridge_footer_subtitle),
        onClick = { bridge.open(text, target, pinned?.code) },
    )
}

/** Try it's "Say it in …" button, next to Check; its language follows what's typed. */
@Composable
fun BridgeTryItButton(bridge: BridgeViewModel, text: String, pinned: Language?, modifier: Modifier = Modifier) {
    val target = defaultBridgeTarget(text, pinned?.code, bridge.targets) ?: return
    LinguizeButton(
        stringResource(R.string.lab_language_bridge_say_it_in, bridgeLanguageName(target)),
        { bridge.open(text, target, pinned?.code) },
        ButtonKind.Outlined,
        modifier,
    )
}

/** Shows the bridge page while it's open, and [content] (the check card) otherwise. */
@Composable
fun BridgeHost(
    bridge: BridgeViewModel,
    actions: BridgeActions,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = bridge.state
    if (state == null) content() else BridgeCard(state, bridge.targets, actions, modifier, scrollable)
}

/**
 * The page's actions for a host: Use this (or Put back) replaces the writer's text through
 * [onReplace], and Check it hands the result and its language to [onCheck]. Both close the page.
 */
@Composable
fun hostBridgeActions(
    bridge: BridgeViewModel,
    onReplace: ((String) -> Unit)?,
    onCheck: (text: String, language: Language?) -> Unit,
    onOpenSettings: (() -> Unit)?,
    useLabel: Int = R.string.lab_language_bridge_use,
    checkNote: Int? = null,
): BridgeActions {
    val context = LocalContext.current
    return BridgeActions(
        onBack = bridge::close,
        onTarget = bridge::changeTarget,
        onRetry = bridge::retry,
        onOpenSettings = onOpenSettings,
        onCopy = { context.copyToClipboard(it) },
        onUse = onReplace?.let { replace -> { text: String -> bridge.close(); replace(text) } },
        useLabel = useLabel,
        onCheck = { text, target -> bridge.close(); onCheck(text, Language.forCode(target)) },
        checkNote = checkNote,
    )
}
