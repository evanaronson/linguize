package com.evanaronson.linguize.ui.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.time.MessageTime
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds one History session's time; null when there's none to show. */
fun interface MessageTimes {
    suspend fun of(sessionId: String): MessageTime?

    companion object {
        /**
         * The app's: the session's record, or for a History session with none (an older check)
         * its writing estimated and review not timed, as Time spent counts it. Null when the
         * session isn't in History (deleted) or couldn't be read.
         */
        fun of(store: TimeStore, history: HistoryStore) = MessageTimes { sessionId ->
            val records = store.all()
            MessageTime.of(sessionId, records) ?: run {
                val session = (history.detail(sessionId) as? SessionDetailResult.Found)?.detail?.session ?: return@run null
                val text = SessionText(session.id, session.origin, session.targetLanguage, session.text, session.closedAt ?: session.startedAt)
                withContext(Dispatchers.Default) { MessageTime.of(text.untimed(), records) }
            }
        }
    }
}

/**
 * Lab, Time spent: where one message's time comes from. Null uses the app's time store; tests
 * provide their own. Outside the app (no [App]) nothing shows.
 */
val LocalMessageTimes = staticCompositionLocalOf<MessageTimes?> { null }

/**
 * The time of History session [sessionId], read once it's asked for; null while reading, when
 * the session is gone from History, or when there's no session (History off).
 */
@Composable
fun rememberMessageTime(sessionId: String?): MessageTime? {
    val times = LocalMessageTimes.current
        ?: (LocalContext.current.applicationContext as? App)?.let { app -> remember(app) { MessageTimes.of(app.timeStore, app.history) } }
    val time by produceState<MessageTime?>(null, sessionId, times) {
        value = if (sessionId == null || times == null) null else times.of(sessionId)
    }
    return time
}

/**
 * One message's time as one quiet line: "Writing 1 min 20 s, reviewing 35 s", for another
 * app "Writing about 40 s (estimated), reviewing 35 s", and for an older check, whose review
 * wasn't timed, "Writing about 40 s (estimated), review not timed".
 */
@Composable
fun messageTimeText(time: MessageTime): String {
    val writing = duration(time.writingMs, precise = true)
    val review = time.reviewMs ?: return stringResource(
        if (time.writingEstimated) R.string.lab_time_spent_message_estimated_untimed else R.string.lab_time_spent_message_untimed,
        writing,
    )
    return stringResource(
        if (time.writingEstimated) R.string.lab_time_spent_message_estimated else R.string.lab_time_spent_message,
        writing, duration(review, precise = true),
    )
}

/** [rememberMessageTime] as its line; null when there's nothing to show. */
@Composable
fun messageTimeLine(sessionId: String?): String? = rememberMessageTime(sessionId)?.let { messageTimeText(it) }
