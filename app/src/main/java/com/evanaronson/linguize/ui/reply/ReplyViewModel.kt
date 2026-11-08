package com.evanaronson.linguize.ui.reply

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.reply.ReplyDraft
import com.evanaronson.linguize.core.reply.ReplyIntent
import com.evanaronson.linguize.core.reply.ReplyIntents
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.reply.ReplyPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The message being answered and the translation the writer read. */
data class IncomingMessage(val text: String, val translation: String)

/** Ways to answer, proposed by Linguize. They are a shortcut: typing is always possible. */
sealed interface ReplySuggestions {
    data object Loading : ReplySuggestions
    data class Ready(val intents: List<ReplyIntent>) : ReplySuggestions

    /** Suggestions failed; the writer can still say what they want to answer. */
    data object Unavailable : ReplySuggestions
}

/** Where the reply is: being described, drafted, ready to read and edit, or failed. */
sealed interface ReplyState {
    data object Describing : ReplyState
    data class Drafting(val intent: String) : ReplyState
    data class Drafted(val intent: String, val draft: ReplyDraft) : ReplyState
    data class Failed(val intent: String, val failure: CheckFailure) : ReplyState
}

/**
 * Lab: Help me reply. Takes a message the writer has understood, what they want to say back,
 * and drafts a short reply with a gloss per sentence. Nothing is saved or sent; the host
 * copies the reply or puts it back. Late answers from a replaced request are ignored.
 */
class ReplyViewModel(
    private val suggest: suspend (message: String, translation: String) -> ReplyIntents,
    private val write: suspend (message: String, translation: String, intent: String, simplerThan: String?) -> ReplyDraft,
) : ViewModel() {
    var message by mutableStateOf<IncomingMessage?>(null)
        private set
    var suggestions by mutableStateOf<ReplySuggestions>(ReplySuggestions.Loading)
        private set
    var state by mutableStateOf<ReplyState>(ReplyState.Describing)
        private set

    /** What the writer typed about their answer, kept when they come back to change it. */
    var intent by mutableStateOf("")
        private set

    /** The writer's own version of the drafted reply; null while it is unchanged. */
    var edited by mutableStateOf<String?>(null)
        private set

    private var suggestionsJob: Job? = null
    private var draftJob: Job? = null
    private var suggestionsGeneration = 0L
    private var draftGeneration = 0L
    private var simplerThan: String? = null

    /** The text that Copy or Put back would use: the edit, or the draft as drafted. */
    val reply: String?
        get() = (state as? ReplyState.Drafted)?.let { edited ?: it.draft.text }

    /** Whether the reply differs from the draft its gloss describes. */
    val isEdited: Boolean
        get() {
            val drafted = state as? ReplyState.Drafted ?: return false
            return edited != null && edited != drafted.draft.text
        }

    /** Opens the reply for [text]. The same message keeps its draft; a new one starts over. */
    fun start(text: String, translation: String) {
        val incoming = IncomingMessage(text, translation)
        if (incoming == message) {
            if (suggestions is ReplySuggestions.Unavailable) loadSuggestions()
            return
        }
        cancel()
        message = incoming
        intent = ""
        edited = null
        simplerThan = null
        state = ReplyState.Describing
        loadSuggestions()
    }

    fun changeIntent(value: String) {
        intent = value.take(ReplyPrompt.MAX_INTENT_INPUT)
    }

    /** Drafts a reply that says the typed intent. Returns false when there is nothing to say. */
    fun draftTyped(): Boolean {
        if (intent.isBlank()) return false
        draft(intent.trim(), simpler = null)
        return true
    }

    /** Drafts a reply from one of the suggestions; its instruction says more than its label. */
    fun choose(suggestion: ReplyIntent) {
        intent = suggestion.instruction
        draft(suggestion.instruction, simpler = null)
    }

    /** Drafts again with the same intent, using shorter sentences and commoner words. */
    fun simpler() {
        val drafted = state as? ReplyState.Drafted ?: return
        draft(drafted.intent, simpler = drafted.draft.text)
    }

    fun retry() {
        when (val current = state) {
            is ReplyState.Failed -> draft(current.intent, simplerThan)
            is ReplyState.Drafted -> draft(current.intent, simpler = null)
            else -> Unit
        }
    }

    /** Back to describing the answer, keeping what was typed. */
    fun describeAgain() {
        cancelDraft()
        edited = null
        state = ReplyState.Describing
    }

    fun edit(value: String) {
        if (state !is ReplyState.Drafted) return
        edited = value
    }

    /** Drops the writer's edit, so the reply matches its gloss again. */
    fun undoEdit() {
        edited = null
    }

    fun cancel() {
        suggestionsGeneration++
        suggestionsJob?.cancel()
        suggestionsJob = null
        cancelDraft()
    }

    private fun cancelDraft() {
        draftGeneration++
        draftJob?.cancel()
        draftJob = null
    }

    private fun loadSuggestions() {
        val incoming = message ?: return
        suggestionsGeneration++
        suggestionsJob?.cancel()
        val requested = suggestionsGeneration
        suggestions = ReplySuggestions.Loading
        suggestionsJob = viewModelScope.launch {
            val shown = try {
                ReplySuggestions.Ready(suggest(incoming.text, incoming.translation).intents)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReplySuggestions.Unavailable
            }
            if (requested == suggestionsGeneration) suggestions = shown
        }
    }

    private fun draft(what: String, simpler: String?) {
        val incoming = message ?: return
        cancelDraft()
        val requested = draftGeneration
        simplerThan = simpler
        edited = null
        state = ReplyState.Drafting(what)
        draftJob = viewModelScope.launch {
            val shown = try {
                ReplyState.Drafted(what, write(incoming.text, incoming.translation, what, simpler))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                ReplyState.Failed(what, e)
            } catch (e: Exception) {
                ReplyState.Failed(what, CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
            }
            if (requested == draftGeneration) state = shown
        }
    }

    companion object {
        private suspend fun drafter(app: App) = withContext(Dispatchers.Default) { app.replyDrafter }

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReplyViewModel(
                    // Creating the drafter may read prompt assets: never on the main thread.
                    suggest = { text, translation -> drafter(app).intents(text, translation) },
                    write = { text, translation, intent, simpler -> drafter(app).draft(text, translation, intent, simpler) },
                )
            }
        }
    }
}
