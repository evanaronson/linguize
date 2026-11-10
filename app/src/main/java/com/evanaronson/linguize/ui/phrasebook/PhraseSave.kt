package com.evanaronson.linguize.ui.phrasebook

import com.evanaronson.linguize.core.language.LanguageCodes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhraseSource
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.history.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a check card or saved check knows about where its changes come from: the language
 * (ISO code) and the app. Read when a phrase is saved, so a live card's detected language
 * is current. A card without one (a preview, a test) shows no save control.
 */
class PhraseOrigin(
    val language: () -> String?,
    val app: () -> String? = { null },
    val sessionId: () -> String? = { null },
) {
    companion object {
        /** A saved check: its target language, else what was detected, and its app. */
        fun of(session: SessionRecord) = PhraseOrigin(
            language = { session.targetLanguage ?: session.detectedLanguage },
            app = { session.hostApp },
            sessionId = { session.id },
        )
    }
}

/** The origin for every explanation below; provided by the card's host and by saved checks. */
val LocalPhraseOrigin = staticCompositionLocalOf<PhraseOrigin?> { null }

@Composable
fun ProvidePhraseOrigin(origin: PhraseOrigin?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPhraseOrigin provides origin, content = content)
}

/**
 * The phrase a change offers: its new words, the words they replace, and the reason. Null
 * when there's nothing worth keeping: a deletion, spacing or punctuation alone, or a whole
 * paragraph.
 */
fun phraseCandidate(from: String, to: String, why: String?, kind: EditKind, origin: PhraseOrigin?): PhraseDraft? {
    if (origin == null) return null
    val phrase = to.trim()
    if (phrase.none { it.isLetter() } || phrase.length > PhrasebookStore.MAX_PHRASE) return null
    return PhraseDraft(
        phrase = phrase,
        replaced = from.trim().takeIf { it.any(Char::isLetterOrDigit) },
        meaning = why?.trim()?.takeIf { it.isNotEmpty() },
        language = origin.language(),
        source = if (kind == EditKind.Fix) PhraseSource.Fix else PhraseSource.Rewording,
        app = origin.app(),
        sessionId = origin.sessionId(),
    )
}

/** The language a raw check answer was judged in ("Catalan", "French"), as an ISO 639-1 code. */
fun answerLanguage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val name = try {
        ((Json.parseToJsonElement(raw) as? JsonObject)?.get("language") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    return LanguageCodes.fromEnglishName(name)
}

/**
 * A star that saves [draft] in the Phrasebook, and takes it out again. Shown filled while
 * the phrase is saved, wherever it was saved from.
 */
@Composable
fun SavePhraseButton(draft: PhraseDraft, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { PhrasebookStore.of(context) }
    val phrases by store.phrases.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    val saved = phrases?.firstOrNull { it.matches(draft) }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.lab_phrasebook_save_phrase, draft.phrase)
    val state = stringResource(if (saved != null) R.string.lab_phrasebook_saved else R.string.lab_phrasebook_not_saved)
    IconToggleButton(
        checked = saved != null,
        enabled = phrases != null,
        onCheckedChange = { save ->
            scope.launch(Dispatchers.IO) {
                if (save) store.save(draft) else saved?.let { store.delete(it.id) }
            }
        },
        modifier = modifier.semantics {
            contentDescription = label
            stateDescription = state
        },
    ) {
        Icon(
            painterResource(if (saved != null) R.drawable.ic_lab_phrasebook_star_filled else R.drawable.ic_lab_phrasebook_star),
            contentDescription = null,
            tint = if (saved != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
