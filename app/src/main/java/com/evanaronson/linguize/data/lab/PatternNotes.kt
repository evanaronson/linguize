package com.evanaronson.linguize.data.lab

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One tip of a saved explanation, for the group whose stable token is [pattern]. */
@Serializable
data class SavedTip(val pattern: String, val tip: String)

/**
 * A saved "Explain my patterns" answer: derived data only, with what it was derived from.
 * [sources] are the History session ids whose examples the report used; when any of them is
 * deleted the explanation is deleted too. [fingerprint] identifies the exact evidence and
 * [analyser] the analyser version; [prompt], [provider] and [model] the request.
 */
@Serializable
data class SavedExplanation(
    val summary: String,
    val tips: List<SavedTip>,
    val fingerprint: String,
    val analyser: String,
    val prompt: String,
    val provider: String,
    val model: String,
    val createdAt: Long,
    val sources: List<String>,
    val checks: Int,
)

/** Where the explanation is kept. One slot: a new explanation replaces the old one. */
interface ExplanationStore {
    fun read(): SavedExplanation?
    fun write(explanation: SavedExplanation)
    fun delete()
}

/**
 * Lab-local storage for Your patterns, in its own preferences file `lab_patterns`. History
 * is never written; this file holds only the derived explanation. Call off the main thread.
 */
class PatternNotes(context: Context) : ExplanationStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): SavedExplanation? {
        val raw = runCatching { prefs.getString(EXPLANATION, null) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<SavedExplanation>(raw) }
            .onFailure { Log.w(TAG, "Unreadable saved explanation; deleting it", it); delete() }
            .getOrNull()
    }

    override fun write(explanation: SavedExplanation) {
        prefs.edit().putString(EXPLANATION, json.encodeToString(SavedExplanation.serializer(), explanation)).commit()
    }

    override fun delete() {
        prefs.edit().remove(EXPLANATION).commit()
    }

    companion object {
        const val FILE = "lab_patterns"
        private const val EXPLANATION = "explanation.v1"
        private const val TAG = "PatternNotes"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
