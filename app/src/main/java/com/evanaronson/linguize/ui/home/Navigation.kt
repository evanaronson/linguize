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

    /** Lab: Help me reply. */
    data object LabReply : Screen

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
            LabReply -> "lab_reply"
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
            saved == "lab_reply" -> LabReply
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
