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

/** A text box and the same card the selection menu shows, so checks can be tried here. */
@Composable
internal fun TryIt(check: CheckViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val initialSample = stringResource(R.string.try_it_sample)
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

    LinguizeTextField(
        value = sample,
        onValueChange = {
            sample = it
            blank = false
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
    }

    check.state?.let { state ->
        ResultCard(
            state = state,
            actions = CardActions.of(
                check, context, onClose = ::applyAndClose, onOpenSettings = onOpenSettings,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            scrollable = false,
        )
    }
    Quiet(stringResource(R.string.try_it_note), Modifier.padding(top = 12.dp))
}
