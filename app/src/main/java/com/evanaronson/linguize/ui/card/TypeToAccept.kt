package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import com.evanaronson.linguize.ui.components.LinguizeTextField
import java.text.Normalizer

/**
 * Lab, Type to accept: with the setting on, the open change's Accept button becomes a field,
 * and typing the change exactly accepts it. See docs/lab/type-to-accept.md.
 *
 * The rules, in one place:
 * - The target is the change's new text as the card shows it: line breaks in it read as one
 *   space, and spacing at either end is dropped. Nothing is left of a deletion or a
 *   spacing-only change, so those keep their Accept button ([target] is null).
 * - Exact characters: case, accents and punctuation all count. Text that is the same in
 *   Unicode (é typed as one character or as e plus an accent) is the same, and the three
 *   apostrophes (' ’ ʼ) are one, because keyboards differ in which one they type.
 * - Typed line breaks are dropped: the field is one line of text that may wrap.
 */
internal object TypeToAccept {
    /** What to type for a change whose new text is [replacement]; null when there's nothing to type. */
    fun target(replacement: String): String? =
        replacement.replace(LINE_BREAKS, " ").trim().takeIf { it.isNotEmpty() }

    /** The field's text: what was typed, without line breaks. */
    fun typed(value: String): String = value.filterNot { it in LINE_BREAK_CHARS }

    /** How [typed] compares with [target]. */
    fun progress(typed: String, target: String): Progress {
        if (typed.isEmpty()) return Progress.Empty
        val have = comparable(typed)
        val want = comparable(target)
        return when {
            have == want -> Progress.Match
            want.startsWith(have) -> Progress.OnTrack
            else -> Progress.Off
        }
    }

    /**
     * Decomposed (NFD), so canonically equal text compares equal and a letter still waiting
     * for its accent (a dead key, or e then a combining acute) isn't off yet.
     */
    private fun comparable(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).map { if (it in APOSTROPHES) '\'' else it }.joinToString("")

    enum class Progress { Empty, OnTrack, Off, Match }

    private const val LINE_BREAK_CHARS = "\r\n\u0085  "
    private val LINE_BREAKS = Regex("\\s*[$LINE_BREAK_CHARS]+\\s*")
    private const val APOSTROPHES = "’ʼ"
}

/** One open change to type: [key] is its id, so each change starts with an empty field. */
internal class TypeTarget(val key: Int, val text: String)

/** Whether Type to accept is on; read once per check shown. */
@Composable
internal fun rememberTypeToAccept(check: Any?): Boolean {
    val context = LocalContext.current
    return remember(context, check) { TypeToAcceptPreferences(context).enabled }
}

/**
 * The field that stands in for Accept. It takes focus and opens the keyboard as it appears,
 * turns to the error state the moment the text stops matching, and accepts (the same
 * [onAccept] as the button) as soon as it matches exactly.
 */
@Composable
internal fun TypeToAcceptField(target: String, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    var typed by rememberSaveable { mutableStateOf("") }
    // Once accepted, the field may still show while its panel folds away; it accepts once.
    var accepted by remember { mutableStateOf(false) }
    val off = TypeToAccept.progress(typed, target) == TypeToAccept.Progress.Off
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    LinguizeTextField(
        value = typed,
        onValueChange = { value ->
            if (!accepted) {
                typed = TypeToAccept.typed(value)
                if (TypeToAccept.progress(typed, target) == TypeToAccept.Progress.Match) {
                    accepted = true
                    onAccept()
                }
            }
        },
        modifier = modifier,
        placeholder = stringResource(R.string.lab_type_to_accept_placeholder),
        supporting = if (off) stringResource(R.string.lab_type_to_accept_off) else null,
        isError = off,
        large = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        fieldModifier = Modifier.focusRequester(focus),
        accessibleLabel = stringResource(R.string.lab_type_to_accept_label, target),
        announceSupporting = true,
    )
}
