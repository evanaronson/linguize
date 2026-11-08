package com.evanaronson.linguize.core

/**
 * How the writer wants checks from one app to go: the same person writes casually in a chat
 * app and carefully in email. Every field is optional; null keeps what Settings says.
 */
data class AppRule(
    /** The source app's package name. */
    val app: String,
    /** The language to check as; null means Auto Detect (no pin). */
    val language: Language? = null,
    /** Replaces the register of every writing profile; null keeps each profile's own. */
    val register: WritingRegister? = null,
    /** Replaces Settings' "What to suggest"; null keeps it. */
    val judgments: Judgments? = null,
) {
    init {
        require(app.isNotBlank() && app.length <= 256)
    }

    /** A rule that changes nothing is the same as no rule. */
    val isEmpty: Boolean get() = language == null && register == null && judgments == null
}

/** A choice made on the card for this check only. Null fields fall through to the rule, then Settings. */
data class VoiceOverride(
    val register: WritingRegister? = null,
    val judgments: Judgments? = null,
) {
    val isEmpty: Boolean get() = register == null && judgments == null

    companion object {
        val None = VoiceOverride()
    }
}

/** Where a resolved choice came from, so the card can say so. */
enum class VoiceSource { Settings, Rule, ThisCheck }

/** The register and suggestions a check will run with, each with its source; null values keep Settings. */
data class Voice(
    val register: WritingRegister?,
    val registerSource: VoiceSource,
    val judgments: Judgments?,
    val judgmentsSource: VoiceSource,
) {
    /** True when neither choice changes what Settings would send. */
    val isDefault: Boolean get() = register == null && judgments == null

    /** The strongest source that set anything: this check beats a rule, a rule beats Settings. */
    val source: VoiceSource
        get() = maxOf(registerSource, judgmentsSource)

    companion object {
        val Default = Voice(null, VoiceSource.Settings, null, VoiceSource.Settings)
    }
}

/**
 * Per-app voice resolution. Precedence, strongest first:
 * - language: the writer's explicit choice (a Catalanize/Castilianize entry, or the card's
 *   language picker), then the app's rule, then Auto Detect;
 * - register and suggestions: the card's choice for this check, then the app's rule, then Settings.
 */
object AppVoice {
    /** The language to start a check in: an explicit request wins; Auto Detect yields to a pinned rule. */
    fun language(requested: Language?, rule: AppRule?): Language? = requested ?: rule?.language

    fun resolve(rule: AppRule?, override: VoiceOverride = VoiceOverride.None): Voice {
        val (register, registerSource) = when {
            override.register != null -> override.register to VoiceSource.ThisCheck
            rule?.register != null -> rule.register to VoiceSource.Rule
            else -> null to VoiceSource.Settings
        }
        val (judgments, judgmentsSource) = when {
            override.judgments != null -> override.judgments to VoiceSource.ThisCheck
            rule?.judgments != null -> rule.judgments to VoiceSource.Rule
            else -> null to VoiceSource.Settings
        }
        return Voice(register, registerSource, judgments, judgmentsSource)
    }

    /** [profiles] with [voice]'s register, if any. Identity, language, variety and punctuation stay. */
    fun profiles(profiles: List<WritingProfile>, voice: Voice): List<WritingProfile> {
        val register = voice.register ?: return profiles
        return profiles.map { if (it.register == register) it else it.copy(register = register) }
    }

    /** [judgments] unless [voice] replaces them. */
    fun judgments(judgments: Judgments, voice: Voice): Judgments = voice.judgments ?: judgments

    /**
     * The rule to save from what the card now shows: this check's choices on top of the
     * existing rule, keeping its language. Null when the result changes nothing.
     */
    fun saved(app: String, rule: AppRule?, override: VoiceOverride): AppRule? {
        val merged = AppRule(
            app = app,
            language = rule?.language,
            register = override.register ?: rule?.register,
            judgments = override.judgments ?: rule?.judgments,
        )
        return merged.takeUnless { it.isEmpty }
    }
}
