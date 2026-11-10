package com.evanaronson.linguize

import android.util.Log
import com.evanaronson.linguize.data.chat.ChatStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Finishes conversation deletion after its durable local transaction, including after restart. */
class ChatDeletions(
    private val store: ChatStore,
    private val forgetMemory: suspend (String) -> Unit,
    private val deleteHistory: suspend (String) -> Boolean,
    private val deleteTiming: suspend (Collection<String>) -> Boolean,
    private val forgetExports: suspend () -> Boolean = { true },
) {
    private val draining = Mutex()

    /**
     * One pass over pending work. Every child operation is idempotent; failed work stays on
     * disk for the next startup, chat visit, or deletion. Cancellation also leaves it pending.
     */
    suspend fun drain(): Boolean = draining.withLock {
        val pending = try {
            store.pendingDeletions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read pending conversation cleanup", e)
            return@withLock false
        }
        var complete = !pending.hasUnreadableEntries
        for (deletion in pending.entries) {
            // Forget first: it invalidates queued learning before its first suspension, even
            // if another child fails. Still attempt independent cleanup after a storage error.
            var acknowledged = attempt { forgetMemory("chat:${deletion.conversationId}"); true }
            for (id in deletion.sessionIds) {
                val deleted = attempt { deleteHistory(id) }
                acknowledged = deleted && acknowledged
            }
            val timingDeleted = attempt { deleteTiming(deletion.sessionIds) }
            acknowledged = timingDeleted && acknowledged
            if (deletion.sessionIds.isNotEmpty()) {
                val exportsDeleted = attempt { forgetExports() }
                acknowledged = exportsDeleted && acknowledged
            }
            if (acknowledged) acknowledged = attempt { store.completeDeletion(deletion) }
            complete = acknowledged && complete
        }
        complete
    }

    private suspend fun attempt(action: suspend () -> Boolean): Boolean = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Conversation cleanup needs another attempt", e)
        false
    }

    private companion object {
        const val TAG = "ChatDeletions"
    }
}
