package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.imeVisible
import java.text.Normalizer
import androidx.compose.runtime.withFrameMillis

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
 * Lab, Type to accept: whether the writer is typing a change on this card, so the card and its
 * host can give the text and the field the room (docs/lab/type-to-accept.md, "Room to type").
 * A host that needs to know (Chat, the floating card) provides one with [LocalCardTyping];
 * otherwise [ResultCard] keeps its own.
 */
@Stable
class CardTyping {
    /** The field that has focus, if one does; an identity, so a field leaving can't clear its successor. */
    private var focusedField by mutableStateOf<Any?>(null)

    /** True while a type-to-accept field has focus and the keyboard is up. Set by [TrackTyping]. */
    var active by mutableStateOf(false)
        internal set

    /** The change typed correctly and waiting out its hold before it is accepted; it resolves in the text. */
    var resolving by mutableStateOf<Int?>(null)
        internal set

    /** Where the open field sits, for keeping it in view with the text it changes. Not state. */
    internal var field: LayoutCoordinates? = null

    internal val fieldFocused get() = focusedField != null

    internal fun focused(owner: Any, focused: Boolean) {
        if (focused) focusedField = owner else if (focusedField === owner) focusedField = null
    }
}

/** The [CardTyping] a host shares with its card; null lets the card keep its own. */
val LocalCardTyping = compositionLocalOf<CardTyping?> { null }

/** One [CardTyping] for a host and its card. */
@Composable
fun rememberCardTyping(): CardTyping = remember { CardTyping() }

/**
 * Keeps [CardTyping.active] in step with the field's focus and the keyboard's real visibility
 * (its IME insets). It turns on at once and off only after [TYPING_HANDOFF_MILLIS]: accepting
 * one change disposes its field a frame before the next one takes focus, and the keyboard may
 * blink in between, which shouldn't make the layout jump.
 */
@Composable
internal fun TrackTyping(typing: CardTyping) {
    val now = typing.fieldFocused && imeVisible()
    LaunchedEffect(typing, now) {
        if (now) typing.active = true
        else {
            frameDelay(TYPING_HANDOFF_MILLIS)
            typing.active = false
        }
    }
    // A card that goes away (sent, replaced, closed) leaves its host relaxed.
    DisposableEffect(typing) { onDispose { typing.active = false } }
}

internal const val TYPING_HANDOFF_MILLIS = 250L

/**
 * Waits [millis] by the frame clock, as an animation does, rather than the wall clock: the
 * waits here belong to what's on screen (they pause with it), and tests can drive them.
 */
internal suspend fun frameDelay(millis: Long) {
    val start = withFrameMillis { it }
    do {
        val now = withFrameMillis { it }
    } while (now - start < millis)
}

/**
 * How long a correctly typed change shows as correct before it is accepted and the card moves
 * on. Under about 400 ms the success state isn't registered before it's gone; over a second
 * it feels sluggish when a check has many changes. The system's animator scale shortens the
 * motion around it, not this hold, so the success state always shows.
 */
internal const val CORRECT_HOLD_MILLIS = 700L

/**
 * The field that stands in for Accept. It takes focus and opens the keyboard as it appears,
 * turns to the error state the moment the text stops matching, and on an exact match locks,
 * shows it is correct (a check at its end, read out as "Correct"), resolves the change in the
 * text above ([CardTyping.resolving]), gives the feedback the writer chose, and after
 * [CORRECT_HOLD_MILLIS] accepts through the same [onAccept] as the button, once.
 *
 * One field serves every change typed in the same explanation ([key] is the change): it
 * starts empty for each, and keeps focus, so the keyboard stays up between changes. If the
 * field goes away or moves to another change during the hold (the card closes, Skip, a
 * rotation), the matched change is accepted then, still once.
 */
@Composable
internal fun TypeToAcceptField(target: String, onAccept: () -> Unit, modifier: Modifier = Modifier, key: Int = 0) {
    var typed by rememberSaveable(key) { mutableStateOf("") }
    // The accept a match is waiting on: taken once, by the hold's end or by the field leaving.
    val pending = remember(key) { arrayOfNulls<() -> Unit>(1) }
    var matched by remember(key) { mutableStateOf(false) }
    val off = TypeToAccept.progress(typed, target) == TypeToAccept.Progress.Off
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val typing = LocalCardTyping.current
    val feedback = rememberCorrectFeedback()
    val owner = remember { Any() }
    fun acceptOnce(waiting: Array<(() -> Unit)?>) {
        val accept = waiting[0] ?: return
        waiting[0] = null
        if (typing?.resolving == key) typing.resolving = null
        accept()
    }
    DisposableEffect(typing) {
        onDispose {
            typing?.focused(owner, false)
            if (typing?.field?.isAttached != true) typing?.field = null
        }
    }
    // Leaving mid-hold, or moving on to another change, still accepts the matched one.
    DisposableEffect(key) { onDispose { acceptOnce(pending) } }
    LaunchedEffect(key, matched) {
        if (matched) {
            frameDelay(CORRECT_HOLD_MILLIS)
            acceptOnce(pending)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    LinguizeTextField(
        value = typed,
        onValueChange = { value ->
            // Locked once matched: what's typed during the hold is ignored.
            if (!matched) {
                typed = TypeToAccept.typed(value)
                if (TypeToAccept.progress(typed, target) == TypeToAccept.Progress.Match) {
                    matched = true
                    pending[0] = onAccept
                    typing?.resolving = key
                    feedback.correct()
                }
            }
        },
        modifier = modifier.onGloballyPositioned { typing?.field = it },
        placeholder = stringResource(R.string.lab_type_to_accept_placeholder),
        supporting = if (off) stringResource(R.string.lab_type_to_accept_off) else null,
        // The short message fits beside Skip; screen readers hear where to look.
        spokenSupporting = if (off) stringResource(R.string.lab_type_to_accept_off_spoken) else null,
        isError = off,
        success = if (matched) stringResource(R.string.lab_type_to_accept_correct) else null,
        large = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        fieldModifier = Modifier.focusRequester(focus).onFocusChanged { typing?.focused(owner, it.isFocused) },
        accessibleLabel = stringResource(R.string.lab_type_to_accept_label, target),
        announceSupporting = true,
    )
}

