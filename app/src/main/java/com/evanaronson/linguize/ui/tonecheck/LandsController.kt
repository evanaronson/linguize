package com.evanaronson.linguize.ui.tonecheck

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface LandsState {
    data object Idle : LandsState
    data class Loading(val text: String) : LandsState
    data class Done(val text: String, val landing: Landing) : LandsState
    data class Failed(val text: String, val failure: CheckFailure) : LandsState
}

/**
 * One "How it lands" reading at a time, on demand. Reopening for the same text shows the
 * answer it already has; a reading for text that has since changed is dropped. Readings
 * stay in memory only, for as long as the card is up.
 */
class LandsController(
    private val scope: CoroutineScope,
    private val analyze: suspend (String) -> Landing,
) {
    var state by mutableStateOf<LandsState>(LandsState.Idle)
        private set

    private val cache = object : LinkedHashMap<String, Landing>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Landing>?) = size > CACHE_SIZE
    }
    private var job: Job? = null
    private var generation = 0L

    /** Shows the reading of [text], asking for one only when there isn't one already. */
    fun open(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        when (val current = state) {
            is LandsState.Loading -> if (current.text == message) return
            is LandsState.Done -> if (current.text == message) return
            else -> Unit
        }
        val kept = cache[message]
        if (kept != null) {
            cancel()
            state = LandsState.Done(message, kept)
        } else {
            run(message)
        }
    }

    /** Asks again for the text that failed. */
    fun retry() {
        val failed = state as? LandsState.Failed ?: return
        run(failed.text)
    }

    /** Back to nothing shown; an answer still on its way is dropped. */
    fun close() {
        cancel()
        state = LandsState.Idle
    }

    private fun run(message: String) {
        cancel()
        val requested = generation
        state = LandsState.Loading(message)
        job = scope.launch {
            val shown = try {
                val landing = analyze(message)
                if (requested == generation) cache[message] = landing
                LandsState.Done(message, landing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckFailure) {
                LandsState.Failed(message, e)
            } catch (e: Exception) {
                LandsState.Failed(message, CheckFailure(CheckFailure.Reason.BadResponse, cause = e))
            }
            if (requested == generation) state = shown
        }
    }

    private fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    private companion object {
        const val CACHE_SIZE = 6
    }
}

/** What "How it lands" can do where the card is shown. */
class LandsActions(
    /** Asks the model; throws [CheckFailure]. */
    val analyze: suspend (String) -> Landing,
    val onCopy: (String) -> Unit,
    /** Uses a version as the text to put back, or null to go back; null where text can't be put back. */
    val onUse: ((String?) -> Unit)?,
    /** The version in use, if any. */
    val chosen: () -> String?,
    /** Puts the text back and closes the card; null where there is nothing to put back. */
    val onApply: (() -> Unit)?,
    val onOpenSettings: (() -> Unit)?,
) {
    companion object {
        fun of(
            check: CheckViewModel,
            context: Context,
            canReplace: Boolean,
            onClose: () -> Unit,
            onOpenSettings: (() -> Unit)? = null,
        ) = LandsActions(
            analyze = { App.of(context).landing.landing(it) },
            onCopy = { context.copyToClipboard(it) },
            onUse = if (canReplace) check::useVersion else null,
            chosen = { check.chosenVersion },
            onApply = if (canReplace) onClose else null,
            onOpenSettings = onOpenSettings,
        )
    }
}
