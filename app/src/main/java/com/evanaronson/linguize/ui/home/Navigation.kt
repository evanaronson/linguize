package com.evanaronson.linguize.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.ui.settings.SettingsPage

/** The launcher activity's screens: few enough for a state and a back handler, no navigation library. */
internal sealed interface Screen {
    data object Home : Screen
    data object History : Screen
    data object Settings : Screen

    /** How to set up the accessibility button. */
    data object AccessibilitySetup : Screen

    /** One choice of Settings, on a page of its own. */
    data class SettingsChoice(val page: SettingsPage) : Screen

    /** A past check. */
    data class Detail(val id: String) : Screen

    /** The assumptions of a past check. */
    data class DetailAssumptions(val id: String) : Screen

    /** Lab: saved phrases. */
    data object Phrasebook : Screen
    /** Per-app voice (Lab): the list of apps, or one [app]'s rule. */
    data class AppVoice(val app: String? = null) : Screen
    /** Lab: Fill the gaps. */
    data object FillGaps : Screen
    /** Lab: Help me reply. */
    data object LabReply : Screen
    /** Lab: the reading of a photo of text. */
    data object Photo : Screen
    /** Lab: recurring fixes from saved checks. */
    data object Patterns : Screen

    /** Lab: one group of recurring fixes, by its stable token. */
    data class PatternDetail(val kind: String) : Screen
    /** Lab: practice drills from applied fixes. */
    data object Practice : Screen
    /** Lab: what Linguize remembers about the writer. */
    data object Memory : Screen
    /** Lab: every word the writer typed. */
    data object Words : Screen
    /** Lab: one row of Your words in [language]: [level] is the `WordLevel` token (form, lexeme or lemma). */
    data class WordDetail(val language: String?, val key: String, val level: String) : Screen {
        companion object {
            val LEVELS = setOf("f", "x", "l")
        }
    }
    /** Lab: the chat, which the app opens to unless that is switched off. */
    data object Chat : Screen
    /** Lab: common words used and not yet used. */
    data object VocabGaps : Screen
    /** Lab: your conjugations. */
    data object Conjugations : Screen
    /** Lab: one cell of the conjugation grid, by language and cell token. */
    data class ConjugationCell(val language: String, val cell: String) : Screen

    /** For saved instance state. */
    val saved: String
        get() = when (this) {
            Home -> "home"
            History -> "history"
            Settings -> "settings"
            AccessibilitySetup -> "accessibility"
            is SettingsChoice -> "settings:${page.token}"
            is Detail -> "detail:$id"
            is DetailAssumptions -> "assumptions:$id"
            Phrasebook -> "phrasebook"
            is AppVoice -> if (app == null) "appvoice" else "appvoice:$app"
            FillGaps -> "lab_gap_filler"
            LabReply -> "lab_reply"
            Photo -> "photo"
            Patterns -> "patterns"
            is PatternDetail -> "pattern:$kind"
            Practice -> "practice"
            Memory -> "lab_memory"
            Words -> "words"
            is WordDetail -> "word:$level:${language.orEmpty()}:$key"
            Chat -> "lab_chat"
            VocabGaps -> "vocab_gaps"
            Conjugations -> "conjugations"
            is ConjugationCell -> "conjugation:$language:$cell"
        }

    companion object {
        fun restore(saved: String?): Screen? = when {
            saved == null -> null
            saved == "home" -> Home
            saved == "history" -> History
            saved == "settings" -> Settings
            saved == "accessibility" -> AccessibilitySetup
            saved.startsWith("settings:") -> SettingsPage.read(saved.removePrefix("settings:"))?.let(::SettingsChoice)
            saved.startsWith("detail:") && saved.length > "detail:".length -> Detail(saved.removePrefix("detail:"))
            saved.startsWith("assumptions:") && saved.length > "assumptions:".length ->
                DetailAssumptions(saved.removePrefix("assumptions:"))
            saved == "phrasebook" -> Phrasebook
            saved == "appvoice" -> AppVoice()
            saved.startsWith("appvoice:") && saved.length > "appvoice:".length -> AppVoice(saved.removePrefix("appvoice:"))
            saved == "lab_gap_filler" -> FillGaps
            saved == "lab_reply" -> LabReply
            saved == "photo" -> Photo
            saved == "patterns" -> Patterns
            saved.startsWith("pattern:") && saved.length > "pattern:".length -> PatternDetail(saved.removePrefix("pattern:"))
            saved == "practice" -> Practice
            saved == "lab_memory" -> Memory
            saved == "words" -> Words
            saved.startsWith("word:") -> saved.split(':', limit = 4)
                .takeIf { it.size == 4 && it[3].isNotEmpty() && it[1] in WordDetail.LEVELS }
                ?.let { WordDetail(it[2].ifEmpty { null }, it[3], it[1]) }
            saved == "lab_chat" -> Chat
            saved == "vocab_gaps" -> VocabGaps
            saved == "conjugations" -> Conjugations
            saved.startsWith("conjugation:") -> saved.removePrefix("conjugation:").split(':', limit = 2)
                .takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let { ConjugationCell(it[0], it[1]) }
            else -> null
        }
    }
}

/** The actual path taken, so the same check can return to Recent or to the full History list. */
internal data class Navigation private constructor(private val screens: List<Screen>) {
    constructor() : this(listOf(Screen.Home))

    /** Lab Chat: a path that starts at [root] (Home, or the chat). */
    constructor(root: Screen) : this(listOf(root))

    val current: Screen get() = screens.last()
    val parent: Screen? get() = screens.dropLast(1).lastOrNull()
    val saved: List<String> get() = screens.map { it.saved }

    fun open(screen: Screen): Navigation = when {
        // Home sits right above the chat when the app opens to it.
        screen == Screen.Home -> if (screens.first() == Screen.Chat) Navigation(listOf(Screen.Chat, Screen.Home)) else Navigation()
        screen == Screen.Chat && Screen.Chat in screens -> Navigation(screens.take(screens.indexOf(Screen.Chat) + 1))
        screen == current -> this
        else -> Navigation(screens + screen)
    }

    fun back(): Navigation = if (screens.size > 1) Navigation(screens.dropLast(1)) else this

    companion object {
        fun restore(saved: List<String>?): Navigation {
            val screens = saved.orEmpty().mapNotNull(Screen::restore)
            val root = if (screens.firstOrNull() == Screen.Chat) Navigation(Screen.Chat) else Navigation()
            return screens.fold(root) { navigation, screen -> navigation.open(screen) }
        }
    }
}

/** Keeps the live path's screen state, including ancestors' scroll positions. */
@Composable
internal fun rememberNavigationStateHolder(navigation: Navigation): SaveableStateHolder {
    val saved = rememberSaveableStateHolder()
    var savedScreens by rememberSaveable { mutableStateOf(navigation.saved) }
    val liveScreens = navigation.saved
    SideEffect {
        // Popped screens must not accumulate in the activity's saved Bundle.
        (savedScreens - liveScreens.toSet()).forEach(saved::removeState)
        savedScreens = liveScreens
    }
    return saved
}
