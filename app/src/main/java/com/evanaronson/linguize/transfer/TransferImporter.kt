package com.evanaronson.linguize.transfer

// TEMPORARY: lab transfer bridge, see docs/lab/transfer-bridge.md

import android.content.SharedPreferences
import android.util.Log
import com.evanaronson.linguize.core.language.MyLanguages
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.data.chat.ChatStore
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.data.teachme.LessonStore
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.data.vocabgaps.ImportedDeck
import com.evanaronson.linguize.data.vocabgaps.VocabGapsStore
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.transfer.TransferBundle.Pref
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The stores the bundle merges into, on this app's side. */
class TransferTargets(
    /** Null when History isn't the SQLite store (it always is in the app). */
    val history: SqliteHistoryStore?,
    val historyEnabled: () -> Boolean,
    val chat: ChatStore,
    val time: TimeStore,
    val notes: WriteDraftStore,
    val memory: MemoryStore,
    val phrasebook: PhrasebookStore,
    val lessons: LessonStore,
    val decks: VocabGapsStore,
    val prefs: (String) -> SharedPreferences,
    /** The writer's languages before they're ever edited, as Settings seeds them. */
    val languageSeed: () -> List<String?>,
)

/** Why some of the bundle stayed behind. */
enum class TransferSkip {
    /** History is off here: checks, and the time spent on them, stay behind. */
    HistoryOff,

    /** The two apps keep History in different database versions. */
    HistoryVersion,

    /** Checks still open in the experimental app (a card left open). */
    UnfinishedChecks,

    /** Checks deleted, or waiting to be deleted, in the experimental app. */
    DeletedChecks,

    /** Chats deleted here while their cleanup is pending: never brought back. */
    ChatsDeletedHere,

    /** Lessons beyond what Teach me keeps. */
    LessonsFull,

    /** A patterns explanation only travels with the checks it explains. */
    PatternsWithoutHistory,
}

/** What an import did: counts per kind, what was here already, what stayed behind and what failed. */
data class TransferReport(
    val added: Map<TransferKind, Int>,
    val alreadyHere: Int,
    val skipped: Map<TransferSkip, Int>,
    val failed: Set<TransferKind>,
) {
    val addedAnything get() = added.values.any { it > 0 }
}

/**
 * Merges a [TransferBundle.Incoming] into [targets]. Additive only: nothing here is replaced or
 * deleted, items match by id (lessons by their key, preferences by key), and this app's values
 * win. Running it again adds nothing new. Each kind merges on its own: one failing is reported
 * and the others still merge.
 */
class TransferImporter(private val targets: TransferTargets) {
    private val added = linkedMapOf<TransferKind, Int>()
    private var here = 0
    private val skipped = linkedMapOf<TransferSkip, Int>()
    private val failed = linkedSetOf<TransferKind>()

    suspend fun merge(incoming: TransferBundle.Incoming): TransferReport = withContext(Dispatchers.IO) {
        incoming.unreadable.forEach { part -> TransferKind.entries.firstOrNull { it.part == part }?.let(failed::add) }
        val pendingChats = incoming.chat?.pendingDeletions.orEmpty()
        val excluded = incoming.excludedSessions + pendingChats.flatMap { it.sessionIds }

        val historyKept = kind(TransferKind.Checks) { history(incoming, excluded) } ?: emptySet()
        kind(TransferKind.Time) { time(incoming, excluded, historyKept) }
        incoming.chat?.let { rows ->
            kind(TransferKind.Chats) {
                val result = targets.chat.insertIfAbsent(rows)
                add(TransferKind.Chats, result.conversationsAdded)
                add(TransferKind.Messages, result.messagesAdded)
                here += result.conversationsHere + result.messagesHere
                skip(TransferSkip.ChatsDeletedHere, result.conversationsDeletedHere)
            }
        }
        kind(TransferKind.Notes) { notes(incoming.files) }
        incoming.files[MemoryStore.FILE_NAME]?.let { text ->
            kind(TransferKind.AboutYou) { memory(text, pendingChats.map { it.conversationId }) }
        }
        incoming.files[PhrasebookStore.FILE_NAME]?.let { text -> kind(TransferKind.Phrases) { phrases(text) } }
        incoming.files[LessonStore.FILE_NAME]?.let { text ->
            kind(TransferKind.Lessons) {
                val result = targets.lessons.addIfAbsent(text)
                add(TransferKind.Lessons, result.added)
                here += result.here
                skip(TransferSkip.LessonsFull, result.overCapacity)
            }
        }
        kind(TransferKind.Decks) { decks(incoming.files) }
        for ((name, values) in incoming.prefs) {
            val rule = TransferRules.PREFERENCES[name] ?: continue
            kind(rule.kind) { preferences(name, rule, values.filterKeys(rule::travels), historyKept.isNotEmpty()) }
        }
        TransferReport(added.filterValues { it > 0 }, here, skipped.filterValues { it > 0 }, failed)
    }

    /** Adds the sessions not here yet; returns the ids of the sessions that are here now. */
    private suspend fun history(incoming: TransferBundle.Incoming, excluded: Set<String>): Set<String> {
        val sessions = incoming.sessions
        if (sessions.isEmpty()) return emptySet()
        val history = targets.history ?: throw IllegalStateException("No History store")
        if (!targets.historyEnabled()) {
            skip(TransferSkip.HistoryOff, sessions.size)
            return emptySet()
        }
        if (incoming.historyVersion != TransferRules.historyVersion) {
            skip(TransferSkip.HistoryVersion, sessions.size)
            return emptySet()
        }
        // A check deleted there (or waiting for Undo) stays deleted: it is left out, not copied.
        val (deleted, kept) = sessions.partition { it.id in excluded || it.session["deletedAt"] != null }
        val (open, ready) = kept.partition { it.session["closedAt"] == null }
        skip(TransferSkip.DeletedChecks, deleted.size)
        skip(TransferSkip.UnfinishedChecks, open.size)
        val inserted = history.insertIfAbsent(ready) ?: throw IllegalStateException("History couldn't add the checks")
        add(TransferKind.Checks, inserted)
        here += ready.size - inserted
        return ready.mapNotNull { it.id }.toSet()
    }

    private suspend fun time(incoming: TransferBundle.Incoming, excluded: Set<String>, sessions: Set<String>) {
        // Time goes with its checks: without them a sweep would drop it again.
        val records = incoming.time.filter { it.sessionId in sessions && it.sessionId !in excluded }
        if (records.isEmpty()) return
        val inserted = targets.time.insertIfAbsent(records) ?: throw IllegalStateException("Time spent couldn't be added")
        add(TransferKind.Time, inserted)
        here += records.size - inserted
    }

    private fun notes(files: Map<String, String>) {
        val drafts = files[WriteDraftStore.FILE_NAME]?.let(::writeDrafts)
            ?: files[WriteDraftStore.PAD_FILE_NAME]?.let(::padDrafts)
            ?: return
        val existing = targets.notes.load().drafts.map { it.id }.toMutableSet()
        for (draft in drafts.filterNot { it.isBlank }) {
            if (draft.id in existing) {
                here++
                continue
            }
            if (!targets.notes.restore(draft)) throw IllegalStateException("A note couldn't be added")
            existing += draft.id
            add(TransferKind.Notes, 1)
        }
    }

    private fun memory(text: String, deletedChats: List<String>) {
        var incoming = lenient.decodeFromString(MemoryState.serializer(), text)
        // Notes learned only from a chat deleted there go with it, as its cleanup would do.
        for (conversation in deletedChats) incoming = incoming.forgetting("chat:$conversation")
        var addedFacts = 0
        targets.memory.update { state, _, _ ->
            val ids = state.facts.map { it.id }.toSet()
            val facts = incoming.facts.filter { it.id !in ids }
            addedFacts = facts.size
            here += incoming.facts.size - facts.size
            if (facts.isEmpty()) return@update state
            val cited = facts.flatMap { it.sources }.toSet()
            state.copy(
                facts = (state.facts + facts).sortedBy { it.createdAt },
                // When something was first learned from a source: this app's date wins.
                sources = incoming.sources.filterKeys { it in cited } + state.sources,
            )
        }
        add(TransferKind.AboutYou, addedFacts)
    }

    private fun phrases(text: String) {
        val incoming = lenient.decodeFromString(PhrasebookFile.serializer(), text).phrases
        for (phrase in incoming) {
            val before = targets.phrasebook.load().size
            // Skips a phrase whose id is here, or the same words saved here already.
            targets.phrasebook.restore(phrase)
            if (targets.phrasebook.load().size > before) add(TransferKind.Phrases, 1) else here++
        }
    }

    private fun decks(files: Map<String, String>) {
        val index = files["${VocabGapsStore.DIR}/decks/index.json"] ?: return
        val decks = lenient.decodeFromString(ListSerializer(ImportedDeck.serializer()), index)
        for (deck in decks) {
            val csv = files["${VocabGapsStore.DIR}/decks/${deck.id}.csv"] ?: throw IllegalStateException("A deck's words are missing")
            if (targets.decks.adopt(deck, csv)) add(TransferKind.Decks, 1) else here++
        }
    }

    private fun preferences(name: String, rule: TransferRules.Rule, values: Map<String, Pref>, historyMerged: Boolean) {
        if (values.isEmpty()) return
        val prefs = targets.prefs(name)
        when {
            rule.merge == TransferRules.Merge.LanguageUnion -> languages(prefs, values)
            rule.kind == TransferKind.Patterns && !historyMerged -> skip(TransferSkip.PatternsWithoutHistory, values.size)
            else -> fill(name, rule.kind, prefs, values)
        }
    }

    /** This app's languages in their order, then the ones only the other app has. */
    private fun languages(prefs: SharedPreferences, values: Map<String, Pref>) {
        val incoming = MyLanguages.read((values["languages"] as? Pref.Str)?.value) ?: return
        val current = MyLanguages.read(prefs.getString("languages", null)) ?: MyLanguages.seed(targets.languageSeed())
        val union = incoming.fold(current) { list, code -> MyLanguages.add(list, code) }
        val count = union.size - current.size
        here += incoming.size - count
        if (count == 0) return
        commit(prefs.edit().putString("languages", MyLanguages.write(union)))
        add(TransferKind.Languages, count)
    }

    private fun fill(name: String, kind: TransferKind, prefs: SharedPreferences, values: Map<String, Pref>) {
        val edit = prefs.edit()
        var filled = 0
        for ((key, value) in values) {
            if (name == TransferRules.SETTINGS && key == TransferRules.WRITING_PROFILES) {
                if (prefs.contains(key)) {
                    writingProfiles(prefs, edit, value)
                } else {
                    // Never stored here: the other app's profiles, whole.
                    edit.put(key, value)
                    val records = (value as? Pref.Str)?.value?.let { runCatching { lenient.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                    add(TransferKind.WritingProfiles, records?.size ?: 0)
                }
                continue
            }
            // "Start on" follows the old "Open to chat" switch until it's chosen: either set here wins.
            val chosenHere = name == TransferRules.CHAT && key in setOf(TransferRules.START_ON, TransferRules.OPEN_TO_CHAT) &&
                (prefs.contains(TransferRules.START_ON) || prefs.contains(TransferRules.OPEN_TO_CHAT))
            if (prefs.contains(key) || chosenHere) {
                here++
                continue
            }
            edit.put(key, value)
            filled++
        }
        commit(edit)
        add(kind, filled)
    }

    /** Adds the other app's profile for each language this app has no profile for; never edits one here. */
    private fun writingProfiles(prefs: SharedPreferences, edit: SharedPreferences.Editor, value: Pref) {
        val incoming = (value as? Pref.Str)?.value?.let { runCatching { lenient.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return
        val stored = prefs.getString(TransferRules.WRITING_PROFILES, null)
        // A record here that can't be read must not be replaced.
        val current = stored?.let { runCatching { lenient.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?: throw IllegalStateException("Writing profiles here can't be read")
        fun JsonObject.field(name: String) = runCatching { get(name)?.jsonPrimitive?.content }.getOrNull()
        val languages = current.mapNotNull { (it as? JsonObject)?.field("language") }.toMutableSet()
        val ids = current.mapNotNull { (it as? JsonObject)?.field("id") }.toMutableSet()
        val additions = incoming.mapNotNull { it as? JsonObject }.filter { record ->
            val language = record.field("language") ?: return@filter false
            val id = record.field("id") ?: return@filter false
            (language !in languages && id !in ids).also { if (it) { languages += language; ids += id } }
        }
        here += incoming.size - additions.size
        if (additions.isEmpty()) return
        edit.putString(TransferRules.WRITING_PROFILES, JsonArray(current + additions).toString())
        add(TransferKind.WritingProfiles, additions.size)
    }

    private fun SharedPreferences.Editor.put(key: String, value: Pref) {
        when (value) {
            is Pref.Str -> putString(key, value.value)
            is Pref.Bool -> putBoolean(key, value.value)
            is Pref.IntValue -> putInt(key, value.value)
            is Pref.LongValue -> putLong(key, value.value)
            is Pref.FloatValue -> putFloat(key, value.value)
            is Pref.StringSet -> putStringSet(key, value.value)
        }
    }

    private fun commit(edit: SharedPreferences.Editor) {
        if (!edit.commit()) throw IllegalStateException("Preferences couldn't be saved")
    }

    private fun add(kind: TransferKind, count: Int) {
        if (count > 0) added[kind] = (added[kind] ?: 0) + count
    }

    private fun skip(reason: TransferSkip, count: Int) {
        if (count > 0) skipped[reason] = (skipped[reason] ?: 0) + count
    }

    private suspend fun <T> kind(kind: TransferKind, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't bring in ${kind.part}", e)
        failed += kind
        null
    }

    /** Lab_write.json as WriteDraftStore writes it: `{version, currentId, drafts}`. */
    @Serializable
    private data class WriteFile(val drafts: List<WriteDraft> = emptyList())

    /** The Writing pad's file, which Notes imports once (WriteDraftStore.PadDraft). */
    @Serializable
    private data class PadDraft(val id: String, val title: String = "", val text: String = "", val language: String? = null, val updatedAt: Long = 0)

    @Serializable
    private data class PadFile(val drafts: List<PadDraft> = emptyList())

    @Serializable
    private data class PhrasebookFile(val phrases: List<Phrase> = emptyList())

    private fun writeDrafts(text: String) = lenient.decodeFromString(WriteFile.serializer(), text).drafts

    /** As WriteDraftStore brings the pad in: non-blank drafts, title trimmed. */
    private fun padDrafts(text: String) = lenient.decodeFromString(PadFile.serializer(), text).drafts
        .filter { it.text.isNotBlank() }
        .map { WriteDraft(it.id, it.text, it.language, it.title.trim(), it.updatedAt) }

    private companion object {
        const val TAG = "LabTransfer"
        val lenient = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

private val com.evanaronson.linguize.history.TransferSession.id: String? get() = session["id"] as? String
