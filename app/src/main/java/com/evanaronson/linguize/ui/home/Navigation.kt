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
    /** Lab: common words used and not yet used. */
    data object VocabGaps : Screen

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
            VocabGaps -> "vocab_gaps"
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
            saved == "vocab_gaps" -> VocabGaps
            else -> null
        }
    }
}

/** The actual path taken, so the same check can return to Recent or to the full History list. */
internal data class Navigation private constructor(private val screens: List<Screen>) {
    constructor() : this(listOf(Screen.Home))

    val current: Screen get() = screens.last()
    val parent: Screen? get() = screens.dropLast(1).lastOrNull()
    val saved: List<String> get() = screens.map { it.saved }

    fun open(screen: Screen): Navigation = when (screen) {
        Screen.Home -> Navigation()
        current -> this
        else -> Navigation(screens + screen)
    }

    fun back(): Navigation = if (screens.size > 1) Navigation(screens.dropLast(1)) else this

    companion object {
        fun restore(saved: List<String>?): Navigation = saved.orEmpty()
            .mapNotNull(Screen::restore)
            .fold(Navigation()) { navigation, screen -> navigation.open(screen) }
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
