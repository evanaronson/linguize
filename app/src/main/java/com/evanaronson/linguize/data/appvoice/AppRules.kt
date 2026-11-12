package com.evanaronson.linguize.data.appvoice

import android.content.Context
import android.content.SharedPreferences
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingRegister
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-app voice rules, by package name. An interface so hosts and view models can be tested without Android. */
interface AppRules {
    /** The rule for [app], or null when there is none (or [app] is null). */
    fun rule(app: String?): AppRule?

    /** Every saved rule, by package name. */
    fun all(): List<AppRule>

    /** Saves [rule], replacing any rule for its app. An empty rule deletes it instead. */
    fun save(rule: AppRule)

    /** Resets [app] to Settings. */
    fun reset(app: String)

    /** Resets every app to Settings. */
    fun resetAll()
}

/**
 * [AppRules] in Lab's own preferences file, `lab_app_voice`, apart from Settings: deleting
 * rules never touches the writer's settings, keys or history. Each rule is one key holding
 * stable tokens, so a renamed label never changes what was saved. A value this build can't
 * read is skipped, not deleted.
 */
class SharedPreferencesAppRules(private val prefs: SharedPreferences) : AppRules {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    override fun rule(app: String?): AppRule? {
        if (app.isNullOrBlank()) return null
        return prefs.getString(KEY_PREFIX + app, null)?.let { decode(app, it) }
    }

    override fun all(): List<AppRule> = prefs.all.entries
        .filter { it.key.startsWith(KEY_PREFIX) }
        .mapNotNull { (key, value) -> (value as? String)?.let { decode(key.removePrefix(KEY_PREFIX), it) } }
        .sortedBy { it.app }

    override fun save(rule: AppRule) {
        if (rule.isEmpty) return reset(rule.app)
        val stored = StoredRule(rule.language?.code, rule.register?.token, rule.judgments?.token)
        prefs.edit().putString(KEY_PREFIX + rule.app, json.encodeToString(StoredRule.serializer(), stored)).apply()
    }

    override fun reset(app: String) {
        prefs.edit().remove(KEY_PREFIX + app).apply()
    }

    override fun resetAll() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    @Serializable
    private data class StoredRule(
        val language: String? = null,
        val register: String? = null,
        val judgments: String? = null,
    )

    companion object {
        const val FILE = "lab_app_voice"
        private const val KEY_PREFIX = "rule:"
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads a stored rule; unknown tokens fall back to Settings for that field. */
        internal fun decode(app: String, value: String): AppRule? = try {
            val stored = json.decodeFromString(StoredRule.serializer(), value)
            AppRule(
                app = app,
                language = Language.forCode(stored.language),
                register = WritingRegister.entries.firstOrNull { it.token == stored.register },
                judgments = Judgments.entries.firstOrNull { it.token == stored.judgments },
            ).takeUnless { it.isEmpty }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
