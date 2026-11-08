package com.evanaronson.linguize.ui.appvoice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.appvoice.AppRules
import com.evanaronson.linguize.data.appvoice.SharedPreferencesAppRules
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.ui.history.AppLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an app on the Per-app voice page comes from, in the order the page lists them. */
enum class AppSection { WithRule, Recent, Messaging }

/** One app on the page: its package, name (null when it isn't installed), rule and section. */
data class AppEntry(val app: String, val name: String?, val rule: AppRule?, val section: AppSection)

/**
 * The page's apps: those with a rule first, then apps from recent History, then installed
 * messaging apps; each app once, in its first section. [own] (Linguize itself) is skipped.
 * Apps with a rule and recent apps keep their order; a missing name sorts last.
 */
fun appEntries(
    rules: List<AppRule>,
    recent: List<String?>,
    messaging: List<String>,
    own: String,
    name: (String) -> String?,
): List<AppEntry> {
    val seen = mutableSetOf(own)
    val entries = mutableListOf<AppEntry>()
    val byApp = rules.associateBy { it.app }
    fun add(app: String, section: AppSection) {
        if (app.isBlank() || !seen.add(app)) return
        entries += AppEntry(app, name(app), byApp[app], section)
    }
    rules.sortedWith(compareBy(nullsLast()) { name(it.app)?.lowercase() }).forEach { add(it.app, AppSection.WithRule) }
    recent.filterNotNull().forEach { add(it, AppSection.Recent) }
    // A messaging app that isn't installed has no name and is left out.
    messaging.filter { name(it) != null }.forEach { add(it, AppSection.Messaging) }
    return entries
}

/** Common messaging and email apps; listed only when installed. */
val MessagingApps = listOf(
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.telegram.messenger",
    "org.thoughtcrime.securesms",
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.google.android.gm",
    "com.microsoft.office.outlook",
    "ch.protonmail.android",
    "com.facebook.orca",
    "com.instagram.android",
    "com.google.android.apps.dynamite",
    "com.Slack",
    "com.microsoft.teams",
    "com.discord",
    "com.viber.voip",
    "jp.naver.line.android",
    "com.linkedin.android",
)

/** Per-app voice settings: the apps, their rules, and editing them. */
class AppVoiceSettingsViewModel(
    private val rules: AppRules,
    private val history: HistoryStore,
    private val own: String,
    private val name: (String) -> String?,
    private val messaging: List<String> = MessagingApps,
) : ViewModel() {
    /** Null until the first load. */
    var entries by mutableStateOf<List<AppEntry>?>(null)
        private set

    private var loading: Job? = null

    init {
        refresh()
    }

    /** Reloads the apps; History may have new ones and the card may have saved a rule. */
    fun refresh() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val saved = rules.all()
            entries = withContext(Dispatchers.IO) {
                val recent = runCatching { history.recent(RECENT).first().map { it.hostApp } }.getOrDefault(emptyList())
                appEntries(saved, recent, messaging, own, name)
            }
        }
    }

    fun rule(app: String): AppRule? = entries?.firstOrNull { it.app == app }?.rule ?: rules.rule(app)

    fun setLanguage(app: String, language: Language?) = update(app) { it.copy(language = language) }

    fun setRegister(app: String, register: WritingRegister?) = update(app) { it.copy(register = register) }

    fun setJudgments(app: String, judgments: Judgments?) = update(app) { it.copy(judgments = judgments) }

    fun reset(app: String) {
        rules.reset(app)
        replace(app, null)
    }

    fun resetAll() {
        rules.resetAll()
        refresh()
    }

    private fun update(app: String, change: (AppRule) -> AppRule) {
        val next = change(rule(app) ?: AppRule(app))
        rules.save(next)
        replace(app, next.takeUnless { it.isEmpty })
    }

    /** Updates the entry in place, so the list doesn't jump while the writer edits a rule. */
    private fun replace(app: String, rule: AppRule?) {
        val current = entries ?: return
        entries = if (current.any { it.app == app }) {
            current.map { if (it.app == app) it.copy(rule = rule) else it }
        } else {
            current + AppEntry(app, name(app), rule, AppSection.WithRule)
        }
    }

    companion object {
        /** How many recent checks the page looks at for apps. */
        const val RECENT = 100

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val labels = AppLabels(app.packageManager)
                AppVoiceSettingsViewModel(SharedPreferencesAppRules(app), app.history, app.packageName, labels::of)
            }
        }
    }
}
