package com.evanaronson.linguize.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.ui.progress.ProgressView
import com.evanaronson.linguize.ui.settings.SettingsPage

/** A Progress block supplies its language under Auto; ordinary pages follow the app. */
internal sealed interface RouteScope {
    data object FollowApp : RouteScope
    data class Block(val language: String) : RouteScope {
        init { require(LanguageScope.isCode(language)) }
    }
}

/**
 * The launcher activity's screens: few enough for a state and a back handler, no navigation
 * library. Four are top-level destinations in the bottom bar ([tabs]): Chat, Notes, Progress
 * and History. Everything else opens above one of them.
 */
internal sealed interface Screen {
    /** Only Progress pages and their pattern detail need scope beyond their legacy token. */
    data class Scoped(val page: Screen, val block: RouteScope.Block) : Screen {
        init { require(page.acceptsScope) }
    }
    /** Notes: the list of notes, with New note. Saved as "notes"; "home" (its first name, then Write) still restores. */
    data object Notes : Screen
    data object History : Screen
    data object Settings : Screen

    /** How to set up the accessibility button. */
    data object AccessibilitySetup : Screen

    /** One choice of Settings, on a page of its own. */
    data class SettingsChoice(val page: SettingsPage) : Screen

    /** A past check. */
    data class Detail(val id: String) : Screen


    /** Lab: saved phrases. */
    data object Phrasebook : Screen
    /** Per-app voice (Lab): the list of apps, or one [app]'s rule. */
    data class AppVoice(val app: String? = null) : Screen
    /** Lab: recurring fixes from saved checks. */
    data object Patterns : Screen

    /** Lab: one group of recurring fixes, by its stable token. */
    data class PatternDetail(val kind: String) : Screen
    /** Lab: what Linguize remembers about the writer. */
    data object Memory : Screen
    /** Lab: Words, every word of a frequency deck and every word the writer typed. */
    data object Words : Screen
    /** Lab: one row of Words in [language]: [level] is the `WordLevel` token (form, lexeme or lemma). */
    data class WordDetail(val language: String?, val key: String, val level: String) : Screen {
        companion object {
            val LEVELS = setOf("f", "x", "l")
        }
    }
    /** Lab: the chat, which the app opens to unless "Start on" names another tab. */
    data object Chat : Screen
    /** Lab: your conjugations. */
    data object Conjugations : Screen
    /** Lab: one cell of the conjugation grid, by language and cell token. */
    data class ConjugationCell(val language: String, val cell: String) : Screen
    /** Progress: views learned from History, in one place. */
    data object Progress : Screen
    /** Lab: Time spent writing and reviewing. */
    data object TimeSpent : Screen

    /**
     * Lab: one conversation, above the Chat tab's list of conversations. [id] null is "the
     * current conversation", what the app opens to; it's resolved when shown.
     */
    data class ChatThread(val id: String?) : Screen

    /** Lab, lang-selector: the writer's languages, from the selector's "Add languages…" and from Settings. */
    data object Languages : Screen

    /** Lab, lang-selector: Settings › About › Open-source notices. */
    data object Notices : Screen

    /** Lab, Conjugations page: every verb used in [language], or those of one [cell] token. */
    data class ConjugationVerbs(val language: String, val cell: String?) : Screen
    /**
     * Lab, Notes navigation: one note's page, above the Notes list. [id] null is "the current
     * note", what the app opens to when Notes is the start; it's resolved when shown.
     */
    data class Note(val id: String?, val openedAsCurrent: Boolean = false) : Screen {
        init { require(!openedAsCurrent || id != null) }
    }

    /** A bottom-bar destination: opening one replaces the path above the start destination. */
    val isTab: Boolean get() = this in tabs

    /** For saved instance state. */
    val saved: String
        get() = when (this) {
            is Scoped -> "scoped:${block.language}:${page.saved}"
            Notes -> NOTES
            History -> "history"
            Settings -> "settings"
            AccessibilitySetup -> "accessibility"
            is SettingsChoice -> "settings:${page.token}"
            is Detail -> "detail:$id"
            Phrasebook -> "phrasebook"
            is AppVoice -> if (app == null) "appvoice" else "appvoice:$app"
            Patterns -> "patterns"
            is PatternDetail -> "pattern:$kind"
            Memory -> "lab_memory"
            Words -> "words"
            is WordDetail -> "word:$level:${language.orEmpty()}:$key"
            Chat -> "lab_chat"
            Conjugations -> "conjugations"
            is ConjugationCell -> "conjugation:$language:$cell"
            Progress -> "progress"
            TimeSpent -> "lab_time_spent"
            is ChatThread -> if (id == null) CHAT_THREAD else "$CHAT_THREAD:$id"
            Languages -> "lab_lang_selector"
            Notices -> "lab_lang_selector_notices"
            is ConjugationVerbs -> "conjugation_verbs:$language:${cell.orEmpty()}"
            is Note -> when {
                id == null -> NOTE
                openedAsCurrent -> "$CURRENT_NOTE:$id"
                else -> "$NOTE:$id"
            }
        }

    companion object {
        private const val CHAT_THREAD = "lab_chat_thread"
        private const val NOTES = "notes"
        private const val NOTE = "lab_notes_note"
        private const val CURRENT_NOTE = "lab_notes_current"

        /** The bottom bar, in order. */
        val tabs: List<Screen> get() = listOf(Chat, Notes, Progress, History)

        fun restore(saved: String?): Screen? = when {
            saved == null -> null
            saved.startsWith("scoped:") -> saved.split(':', limit = 3).takeIf {
                it.size == 3 && LanguageScope.isCode(it[1])
            }?.let { parts ->
                // Retired drills return to their parent tab, without retaining a block scope.
                if (parts[2] == "practice") Progress
                else restore(parts[2])?.takeIf { it.acceptsScope }?.inScope(RouteScope.Block(parts[1]))
            }
            saved == NOTES || saved == "home" -> Notes
            saved == "history" -> History
            saved == "settings" -> Settings
            saved == "accessibility" -> AccessibilitySetup
            saved.startsWith("settings:") -> SettingsPage.read(saved.removePrefix("settings:"))?.let(::SettingsChoice)
            saved.startsWith("detail:") && saved.length > "detail:".length -> Detail(saved.removePrefix("detail:"))
            // The separate Assumptions page is gone (Lab, Meaning): its saved pages open the check.
            saved.startsWith("assumptions:") && saved.length > "assumptions:".length ->
                Detail(saved.removePrefix("assumptions:"))
            saved == "phrasebook" -> Phrasebook
            saved == "appvoice" -> AppVoice()
            saved.startsWith("appvoice:") && saved.length > "appvoice:".length -> AppVoice(saved.removePrefix("appvoice:"))
            // Removed experiments: Fill the gaps became part of every check; Help me reply and
            // Understand a photo were removed. Their pages sat above Notes (then Write).
            saved == "lab_gap_filler" || saved == "lab_reply" || saved == "photo" -> Notes
            saved == "patterns" -> Patterns
            saved.startsWith("pattern:") && saved.length > "pattern:".length -> PatternDetail(saved.removePrefix("pattern:"))
            saved == "practice" -> Progress
            saved == "lab_memory" -> Memory
            saved == "words" -> Words
            saved.startsWith("word:") -> saved.split(':', limit = 4)
                .takeIf { it.size == 4 && it[3].isNotEmpty() && it[1] in WordDetail.LEVELS }
                ?.let { WordDetail(it[2].ifEmpty { null }, it[3], it[1]) }
            saved == "lab_chat" -> Chat
            // Vocabulary gaps became part of Words.
            saved == "vocab_gaps" -> Words
            saved == "conjugations" -> Conjugations
            saved.startsWith("conjugation:") -> saved.removePrefix("conjugation:").split(':', limit = 2)
                .takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let { ConjugationCell(it[0], it[1]) }
            saved == "progress" -> Progress
            saved == "lab_time_spent" -> TimeSpent
            saved == CHAT_THREAD -> ChatThread(null)
            saved.startsWith("$CHAT_THREAD:") && saved.length > CHAT_THREAD.length + 1 -> ChatThread(saved.removePrefix("$CHAT_THREAD:"))
            saved == "lab_lang_selector" -> Languages
            saved == "lab_lang_selector_notices" -> Notices
            saved.startsWith("conjugation_verbs:") -> saved.removePrefix("conjugation_verbs:").split(':', limit = 2)
                .takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { ConjugationVerbs(it[0], it[1].ifEmpty { null }) }
            saved == NOTE -> Note(null)
            saved.startsWith("$NOTE:") && saved.length > NOTE.length + 1 -> Note(saved.removePrefix("$NOTE:"))
            saved.startsWith("$CURRENT_NOTE:") && saved.length > CURRENT_NOTE.length + 1 ->
                Note(saved.removePrefix("$CURRENT_NOTE:"), openedAsCurrent = true)
            else -> null
        }
    }
}

private val Screen.acceptsScope: Boolean
    get() = this in listOf(Screen.Patterns, Screen.Words, Screen.Conjugations, Screen.Phrasebook, Screen.TimeSpent) ||
        this is Screen.PatternDetail

/** The view to draw; its scope remains part of the route's saved identity. */
internal val Screen.destination: Screen get() = if (this is Screen.Scoped) page else this
internal val Screen.scope: RouteScope get() = if (this is Screen.Scoped) block else RouteScope.FollowApp

/** A current-note placeholder retains its editor identity when the saved note resolves. */
internal val Screen.saveableKey: String
    get() = when {
        this is Screen.Note && openedAsCurrent -> Screen.Note(null).saved
        else -> saved
    }

internal fun Screen.inScope(scope: RouteScope): Screen = when (scope) {
    RouteScope.FollowApp -> destination
    is RouteScope.Block -> Screen.Scoped(destination, scope)
}

/** Intrinsic detail IDs keep their language; a block is a fallback while the app is Auto. */
internal fun Screen.effectiveLanguage(appLanguage: String?): String? = when (this) {
    is Screen.WordDetail -> language
    is Screen.ConjugationCell -> language
    is Screen.ConjugationVerbs -> language
    is Screen.Scoped -> appLanguage ?: block.language
    else -> appLanguage
}

/** A block is captured only under Auto; unscoped/legacy routes continue to follow the app. */
internal fun progressDestination(view: ProgressView, appLanguage: String?, block: String?): Screen {
    val page = when (view) {
        ProgressView.Words -> Screen.Words
        ProgressView.Conjugations -> Screen.Conjugations
        ProgressView.Patterns -> Screen.Patterns
        ProgressView.Phrasebook -> Screen.Phrasebook
        ProgressView.Time -> Screen.TimeSpent
    }
    return if (appLanguage == null && block != null) page.inScope(RouteScope.Block(block)) else page
}

/**
 * The actual path taken. It starts at the start destination (the "Start on" tab: Chat,
 * Notes, Progress or History); another tab sits right above it, so Back from any tab returns to the start
 * and Back from the start leaves the app. Other screens open above the current one, so the
 * same check can return to History or to Your patterns.
 */
internal data class Navigation private constructor(private val screens: List<Screen>) {
    constructor() : this(listOf(Screen.Notes))

    /** A path that starts at [root], one of the [Screen.tabs]. */
    constructor(root: Screen) : this(listOf(root))

    val current: Screen get() = screens.last()
    val parent: Screen? get() = screens.dropLast(1).lastOrNull()
    val saved: List<String> get() = screens.map { it.saved }
    val saveableKeys: List<String> get() = screens.map { it.saveableKey }

    /** The bottom bar's selected destination: the tab the current screen was opened from. */
    val tab: Screen get() = screens.last { it.isTab || it == screens.first() }

    /** A delayed callback belongs to this exact live path, including leave/reenter of the same route. */
    fun owns(entry: Navigation): Boolean = this === entry

    fun open(screen: Screen): Navigation = when {
        screen.isTab -> if (screen == screens.first()) Navigation(screens.take(1)) else Navigation(listOf(screens.first(), screen))
        screen == current -> this
        // One conversation at a time: another replaces the open one, so Back still leads to the list.
        screen is Screen.ChatThread && current is Screen.ChatThread -> replace(screen)
        // Likewise one note at a time, so Back leads to the Notes list.
        screen is Screen.Note && current is Screen.Note -> replace(screen)
        else -> Navigation(screens + screen)
    }

    fun back(): Navigation = if (screens.size > 1) Navigation(screens.dropLast(1)) else this

    /** The current screen, swapped for [screen] (the chat's "current conversation" once it's known). */
    fun replace(screen: Screen): Navigation =
        if (screens.size > 1) Navigation(screens.dropLast(1) + screen) else Navigation(listOf(screen))

    /** Name the acknowledged note without replacing the editor that opened as "current". */
    fun resolveNote(id: String): Navigation {
        val note = current as? Screen.Note ?: return this
        return if (note.id == null) replace(Screen.Note(id, openedAsCurrent = true)) else this
    }

    /**
     * Lab, Language scope: the writer switched the app's language. A conversation keeps its
     * own language, so an open one closes to the list, which now shows the new language's.
     */
    fun afterLanguageSwitch(): Navigation = afterLanguageChange().let {
        if (it.current is Screen.ChatThread) it.back() else it
    }

    /** Only an actual app-language transition clears block overrides; restoration never calls this. */
    fun afterLanguageChange(): Navigation =
        if (screens.any { it is Screen.Scoped }) Navigation(screens.map { it.destination }) else this

    companion object {
        fun restore(saved: List<String>?): Navigation {
            val screens = saved.orEmpty().mapNotNull(Screen::restore)
            // The first tab saved is the start; a path that starts elsewhere (an old one) starts at Notes.
            val root = screens.firstOrNull()?.takeIf { it.isTab }?.let { Navigation(it) } ?: Navigation()
            return screens.fold(root) { navigation, screen -> navigation.open(screen) }
        }
    }
}

/** Keeps the live path's screen state, including ancestors' scroll positions. */
@Composable
internal fun rememberNavigationStateHolder(navigation: Navigation): SaveableStateHolder {
    val saved = rememberSaveableStateHolder()
    var savedScreens by rememberSaveable { mutableStateOf(navigation.saveableKeys) }
    val liveScreens = navigation.saveableKeys
    SideEffect {
        // Popped screens must not accumulate in the activity's saved Bundle.
        (savedScreens - liveScreens.toSet()).forEach(saved::removeState)
        savedScreens = liveScreens
    }
    return saved
}
