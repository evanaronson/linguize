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
import com.evanaronson.linguize.CheckContext
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.AppVoice
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Voice
import com.evanaronson.linguize.core.VoiceOverride
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.data.appvoice.AppRules
import com.evanaronson.linguize.data.appvoice.SharedPreferencesAppRules
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.history.AppLabels
import com.evanaronson.linguize.withVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The per-app voice of one open card: the source app's rule, read once when the card opens,
 * and what the writer picked on the card for this check. [attach] makes a [CheckViewModel]
 * run every check (and re-check) with it, so the rule lands in the request and its history
 * snapshot through [CheckContext]. Lives as long as the card's host, beside its check.
 */
class CardVoiceViewModel(
    private val rules: AppRules,
    private val label: (String) -> String? = { null },
) : ViewModel() {
    /** The source app's package name; null when the host doesn't know it. */
    var app by mutableStateOf<String?>(null)
        private set

    /** The app's name, once looked up; null until then or when it isn't installed. */
    var appName by mutableStateOf<String?>(null)
        private set

    /** The app's saved rule as it was when the card opened, or as saved from the card. */
    var rule by mutableStateOf<AppRule?>(null)
        private set

    /** The writer's choices on this card, for this check only. */
    var override by mutableStateOf(VoiceOverride.None)
        private set

    /** What the next check runs with. */
    val voice: Voice get() = AppVoice.resolve(rule, override)

    /** Whether the card says anything: only when an app rule or a choice on the card is in effect. */
    val visible: Boolean get() = app != null && (rule != null || !override.isEmpty)

    /**
     * Starts the card for text from [app]: reads its rule and forgets earlier card choices.
     * Returns the language to check in: [requested] when the writer asked for one, else the rule's.
     */
    fun start(app: String?, requested: Language?): Language? {
        val known = app?.takeIf { it.isNotBlank() }
        this.app = known
        rule = rules.rule(known)
        override = VoiceOverride.None
        appName = null
        if (known != null) {
            viewModelScope.launch {
                val name = withContext(Dispatchers.IO) { runCatching { label(known) }.getOrNull() }
                if (this@CardVoiceViewModel.app == known) appName = name
            }
        }
        return AppVoice.language(requested, rule)
    }

    /** [context] with this card's voice; what [attach] hands the check. */
    fun adjust(context: CheckContext): CheckContext = context.withVoice(voice)

    /** Makes [check] run with this voice from now on. */
    fun attach(check: CheckViewModel) {
        check.adjustContext = ::adjust
    }

    /** Picks a register for this check. Returns true when what the check runs with changed. */
    fun chooseRegister(register: WritingRegister): Boolean =
        choose(override.copy(register = register.takeUnless { it == rule?.register }))

    /** Picks what to suggest for this check. Returns true when what the check runs with changed. */
    fun chooseJudgments(judgments: Judgments): Boolean =
        choose(override.copy(judgments = judgments.takeUnless { it == rule?.judgments }))

    private fun choose(next: VoiceOverride): Boolean {
        val before = voice
        override = next
        return voice.register != before.register || voice.judgments != before.judgments
    }

    /** Saves this card's choices as the app's rule, keeping its language. Nothing to check again. */
    fun saveRule() {
        val app = app ?: return
        val saved = AppVoice.saved(app, rule, override)
        if (saved == null) rules.reset(app) else rules.save(saved)
        rule = saved
        override = VoiceOverride.None
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val labels = AppLabels(app.packageManager)
                CardVoiceViewModel(SharedPreferencesAppRules(app), labels::of)
            }
        }
    }
}
