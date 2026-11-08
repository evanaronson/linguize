package com.evanaronson.linguize.ui.chat

import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException

/** A partner message's translation, as the bubble shows it. */
sealed interface Translation {
    data object Loading : Translation
    data class Shown(val understood: Understood) : Translation
    data class Failed(val failure: CheckFailure) : Translation
}

/**
 * Lab Chat: translations of partner messages into the writer's own language, through the
 * Understand path ([translate]). Each is kept in memory by message id, so showing it again is
 * instant; hiding keeps it. Translations are model output: they are never saved, recorded in
 * History or put in the corpus. Call from one thread (the main thread).
 */
class Translations(private val translate: suspend (String) -> Understood) {
    private val kept = HashMap<String, Translation>()
    private val open = HashSet<String>()

    /** What message [id] shows under it: null when its translation is hidden or was never asked for. */
    fun shown(id: String): Translation? = kept[id]?.takeIf { id in open }

    fun isOpen(id: String) = id in open

    /**
     * The writer tapped Translate (or Hide) on message [id] with [text]. Returns true when a
     * translation has to be fetched: call [load] then. A failed one is fetched again (retry).
     */
    fun toggle(id: String): Boolean {
        if (id in open && kept[id] !is Translation.Failed) {
            open -= id
            return false
        }
        open += id
        val current = kept[id]
        if (current is Translation.Shown || current is Translation.Loading) return false
        kept[id] = Translation.Loading
        return true
    }

    /** Fetches [id]'s translation of [text]; the result stays for later. */
    suspend fun load(id: String, text: String) {
        kept[id] = try {
            Translation.Shown(translate(text))
        } catch (e: CancellationException) {
            kept.remove(id)
            throw e
        } catch (e: CheckFailure) {
            Translation.Failed(e)
        } catch (e: Exception) {
            Translation.Failed(CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
        }
    }

    /** Forgets everything, as when another conversation opens. */
    fun clear() {
        kept.clear()
        open.clear()
    }
}
