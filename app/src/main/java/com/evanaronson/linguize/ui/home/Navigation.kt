package com.evanaronson.linguize.ui.home

/** Visiting the app never ends the check that opened it. Back follows the visible journey. */
internal data class Navigation(
    val screen: Screen = Screen.Home,
    val backToCard: Boolean = false,
    val beforeCard: Screen? = null,
    val settingsFromHome: Boolean = false,
) {
    fun fromCard(existing: Boolean) = copy(
        screen = Screen.Settings,
        backToCard = true,
        beforeCard = if (backToCard) beforeCard else if (existing) screen else null,
        settingsFromHome = false,
    )

    fun settings() = copy(screen = Screen.Settings, settingsFromHome = true)
    fun home() = copy(screen = Screen.Home)
    fun detail(id: String) = copy(screen = Screen.Detail(id))
    fun leaveCard() = copy(backToCard = false, beforeCard = null)

    enum class Back { Home, Card, Exit }

    val back: Back
        get() = when (screen) {
            Screen.Settings -> if (backToCard && !settingsFromHome) Back.Card else Back.Home
            Screen.Home -> if (backToCard) Back.Card else Back.Exit
            is Screen.Detail -> Back.Home
        }
}

/** The launcher activity's screens: few enough for a state and a back handler, no navigation library. */
internal sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen

    /** A past check. */
    data class Detail(val id: String) : Screen

    /** For saved instance state. */
    val saved: String
        get() = when (this) {
            Home -> "home"
            Settings -> "settings"
            is Detail -> "detail:$id"
        }

    companion object {
        fun restore(saved: String?): Screen? = when {
            saved == null -> null
            saved == "settings" -> Settings
            saved.startsWith("detail:") -> Detail(saved.removePrefix("detail:"))
            else -> Home
        }
    }
}
