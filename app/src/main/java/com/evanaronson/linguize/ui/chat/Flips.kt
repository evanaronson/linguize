package com.evanaronson.linguize.ui.chat

import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException

/** What a partner bubble shows when flipped: its translation, or the state of fetching one for an older reply. */
sealed interface Flipped {
    data class Text(val translation: String, val notes: List<String> = emptyList()) : Flipped
    data object Loading : Flipped
    data class Failed(val failure: CheckFailure) : Flipped
}

/**
 * Lab Chat: which partner bubbles are flipped to their translation. A reply carries its own
 * translation; an older one saved without it gets one from Understand ([translate]) the first
 * time it is flipped, kept in memory. Translations are model output: never saved in History or
 * put in the corpus. Call from one thread (the main thread).
 */
class Flips(private val translate: suspend (String) -> Understood) {
    var flipped: Set<String> = emptySet()
        private set
    private val fetched = HashMap<String, Flipped>()

    /** What [message] shows: null for the original, else its translation (or the fetch's state). */
    fun view(message: ChatMessage): Flipped? {
        if (message.id !in flipped) return null
        message.translation?.let { return Flipped.Text(it) }
        return fetched[message.id] ?: Flipped.Loading
    }

    /**
     * A tap on [message]: flips it to its translation or back. Returns true when a translation
     * has to be fetched first: call [load]. A failed fetch is tried again on the next flip.
     */
    fun flip(message: ChatMessage): Boolean {
        if (message.fromWriter) return false
        if (message.id in flipped) {
            flipped = flipped - message.id
            return false
        }
        flipped = flipped + message.id
        if (message.translation != null) return false
        val known = fetched[message.id]
        if (known is Flipped.Text || known == Flipped.Loading) return false
        fetched[message.id] = Flipped.Loading
        return true
    }

    /** Fetches [message]'s translation; the result stays for later flips. */
    suspend fun load(message: ChatMessage) {
        fetched[message.id] = try {
            val understood = translate(message.text)
            Flipped.Text(understood.translation, understood.notes.take(MAX_NOTES).map { "${it.quote}: ${it.explanation}" })
        } catch (e: CancellationException) {
            fetched.remove(message.id)
            throw e
        } catch (e: CheckFailure) {
            Flipped.Failed(e)
        } catch (e: Exception) {
            Flipped.Failed(CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
        }
    }

    companion object {
        const val MAX_NOTES = 3
    }
}
