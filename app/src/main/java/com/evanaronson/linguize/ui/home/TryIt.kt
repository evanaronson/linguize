package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LanguagePicker
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.hearit.DictateButton
import com.evanaronson.linguize.ui.gaps.GapOffer
import com.evanaronson.linguize.ui.languagebridge.BridgeFooterEntry
import com.evanaronson.linguize.ui.languagebridge.BridgeHost
import com.evanaronson.linguize.ui.languagebridge.BridgeTryItButton
import com.evanaronson.linguize.ui.languagebridge.hostBridgeActions
import com.evanaronson.linguize.ui.languagebridge.rememberBridgeViewModel
import com.evanaronson.linguize.ui.replycontext.TryItReplyContext

/** A text box and the same card the selection menu shows, so checks can be tried here. */
@Composable
internal fun TryIt(check: CheckViewModel, onOpenSettings: () -> Unit, onFillGaps: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val initialSample = stringResource(R.string.try_it_sample)
    val bridge = rememberBridgeViewModel()
    var sample by rememberSaveable { mutableStateOf(initialSample) }

    fun applyAndClose() {
        check.workingText?.let { sample = Selection.of(sample).with(it) }
        check.dismiss()
    }

    // Set when a check was asked for with nothing in the box; cleared by typing.
    var blank by rememberSaveable { mutableStateOf(false) }
    // Saved as the language's code (null for auto-detect).
    var code by rememberSaveable { mutableStateOf<String?>(null) }

    // Checks made here are never recorded in history (Origin.Tester isn't recorded): only
    // text from other apps is.
    fun run(language: Language?) {
        val text = sample.trim()
        if (text.isEmpty()) {
            blank = true
        } else {
            blank = false
            check.check(text, language, Origin.Tester)
        }
    }

    TryItReplyContext(check, Modifier.padding(bottom = 12.dp))
    LinguizeTextField(
        value = sample,
        onValueChange = {
            sample = it
            blank = false
            bridge.close()
            // The card's suggestions are for the old text, and nothing of them was applied.
            check.dismiss(applied = false)
        },
        placeholder = stringResource(R.string.try_it_placeholder),
        accessibleLabel = stringResource(R.string.your_text),
        supporting = if (blank) stringResource(R.string.no_text_to_check) else null,
        isError = blank,
        large = true,
        minLines = 2,
    )
    // The button checks with the language shown; picking a language also runs the check.
    FlowRow(Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LanguagePicker(Language.forCode(code), onSelect = {
            code = it?.code
            run(it)
        })
        LinguizeButton(stringResource(R.string.check), { run(Language.forCode(code)) }, ButtonKind.Filled)
        DictateButton(Language.forCode(code), sample, initialSample, onText = {
            sample = it
            blank = false
            check.dismiss(applied = false)
        })
        BridgeTryItButton(bridge, sample.trim(), Language.forCode(code))
    }
    com.evanaronson.linguize.ui.tonecheck.TryItLands(sample, onUse = { sample = it; check.dismiss(applied = false) }, onOpenSettings)

    val bridgeActions = hostBridgeActions(bridge, onReplace = { sample = it; check.dismiss(applied = false) },
        onCheck = { text, language -> sample = text; code = language?.code; run(language) }, onOpenSettings)
    BridgeHost(bridge, bridgeActions, Modifier.fillMaxWidth().padding(top = 12.dp), scrollable = false) {
    check.state?.let { state ->
        ResultCard(
            state = state,
            actions = CardActions.of(
                check, context, onClose = ::applyAndClose, onOpenSettings = onOpenSettings, replyContext = false,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            scrollable = false,
            gapOffer = onFillGaps?.let { open -> GapOffer.of(sample.trim()) { open(sample.trim()) } },
            bridgeEntry = { BridgeFooterEntry(bridge, check, Language.forCode(code)) },
        )
    }
    }
    Quiet(stringResource(R.string.try_it_note), Modifier.padding(top = 12.dp))
}
