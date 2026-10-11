package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.LanguagePicker

/** A text box and the same card the selection menu shows, so checks can be tried here. */
@Composable
internal fun TryIt(check: CheckViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var sample by rememberSaveable { mutableStateOf(SAMPLE) }

    fun applyAndClose() {
        check.workingText?.let { sample = it }
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

    OutlinedTextField(
        value = sample,
        onValueChange = {
            sample = it
            blank = false
            // The card's suggestions are for the old text, and nothing of them was applied.
            check.dismiss(applied = false)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Try it") },
        placeholder = { Text("Type or paste text to check") },
        supportingText = { Text(if (blank) "No text to check." else "Checks here aren't saved.") },
        isError = blank,
        minLines = 2,
    )
    // The button checks with the language shown; picking a language also runs the check.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguagePicker(Language.forCode(code), onSelect = {
            code = it?.code
            run(it)
        })
        Button(onClick = { run(Language.forCode(code)) }) { Text("Check") }
    }

    check.state?.let { state ->
        ResultCard(
            state = state,
            actions = CardActions.of(check, context, onClose = ::applyAndClose, onOpenSettings = onOpenSettings),
            modifier = Modifier.fillMaxWidth(),
            scrollable = false,
        )
    }
}

private const val SAMPLE = "Bon dia! Com estas amb la pluja?"
