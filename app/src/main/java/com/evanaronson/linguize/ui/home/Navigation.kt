package com.evanaronson.linguize.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue

/** The launcher activity's screens: few enough for a state and a back handler, no navigation library. */
internal sealed interface Screen {
    data object Home : Screen
    data object History : Screen
    data object Settings : Screen

    /** A past check. */
    data class Detail(val id: String) : Screen

    /** For saved instance state. */
    val saved: String
        get() = when (this) {
            Home -> "home"
            History -> "history"
            Settings -> "settings"
            is Detail -> "detail:$id"
        }

    companion object {
        fun restore(saved: String?): Screen? = when {
            saved == null -> null
            saved == "home" -> Home
            saved == "history" -> History
            saved == "settings" -> Settings
            saved.startsWith("detail:") && saved.length > "detail:".length -> Detail(saved.removePrefix("detail:"))
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
