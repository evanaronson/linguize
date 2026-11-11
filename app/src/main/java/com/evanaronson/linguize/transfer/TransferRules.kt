package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import com.evanaronson.linguize.data.appvoice.SharedPreferencesAppRules
import com.evanaronson.linguize.data.lab.PatternNotes
import com.evanaronson.linguize.data.language.MyLanguagesStore
import com.evanaronson.linguize.history.HistorySchema

/** What the summary counts, one line each. [part] names it in logs and in the bundle. */
enum class TransferKind(val part: String) {
    Checks("history"),
    Chats("chat"),
    Messages("chat_messages"),
    Time("time"),
    Notes("notes"),
    AboutYou("memory"),
    Phrases("phrasebook"),
    Lessons("lessons"),
    Decks("decks"),
    Languages("languages"),
    AppVoice("app_voice"),
    WritingProfiles("writing_profiles"),
    Patterns("patterns"),
    Preferences("preferences"),
}

/**
 * Which preferences travel, and how each merges. Anything not listed stays behind: API keys
 * (`keys`), History's pending deletions (`recent`), the install's device id, the History
 * switch and its epoch, and preference keys a newer build may add.
 */
object TransferRules {
    /** The History database version both sides must share for rows to travel as they are. */
    val historyVersion: Int get() = HistorySchema.VERSION

    /** How one preferences file merges. */
    enum class Merge {
        /** Each key is set only when this app has never set it: this app's values win. */
        FillIfAbsent,

        /** The writer's languages: this app's in its order, then the ones it lacks. */
        LanguageUnion,
    }

    class Rule(val kind: TransferKind, val merge: Merge, private val keys: Set<String>, private val prefixes: Set<String> = emptySet()) {
        fun travels(key: String) = key in keys || prefixes.any(key::startsWith)
    }

    const val SETTINGS = "settings"
    const val WRITING_PROFILES = "writingProfiles.v1"
    const val CHAT = "lab_chat"
    const val START_ON = "startOn"
    const val OPEN_TO_CHAT = "openToChat"

    /** By preferences file name. Settings' writing profiles merge per language ([WRITING_PROFILES]). */
    val PREFERENCES: Map<String, Rule> = linkedMapOf(
        SETTINGS to Rule(
            TransferKind.Preferences, Merge.FillIfAbsent,
            setOf("provider", "popupPosition", "punctuation", "judgments", WRITING_PROFILES), setOf("model."),
        ),
        CHAT to Rule(TransferKind.Preferences, Merge.FillIfAbsent, setOf(START_ON, OPEN_TO_CHAT, "lastLanguage", "translateHintSeen")),
        "lab_language_scope" to Rule(TransferKind.Preferences, Merge.FillIfAbsent, setOf("current")),
        MyLanguagesStore.FILE to Rule(TransferKind.Languages, Merge.LanguageUnion, setOf("languages")),
        SharedPreferencesAppRules.FILE to Rule(TransferKind.AppVoice, Merge.FillIfAbsent, emptySet(), setOf("rule:")),
        PatternNotes.FILE to Rule(TransferKind.Patterns, Merge.FillIfAbsent, setOf("explanation.v1")),
    )
}
