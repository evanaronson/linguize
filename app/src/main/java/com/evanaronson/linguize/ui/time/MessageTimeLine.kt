package com.evanaronson.linguize.ui.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.time.MessageTime

/** Finds one History session's time; null when none was recorded. */
fun interface MessageTimes {
    suspend fun of(sessionId: String): MessageTime?
}

/**
 * Lab, Time spent: where one message's time comes from. Null uses the app's time store; tests
 * provide their own. Outside the app (no [App]) nothing shows.
 */
val LocalMessageTimes = staticCompositionLocalOf<MessageTimes?> { null }

/**
 * The time of History session [sessionId], read once it's asked for; null while reading, when
 * nothing was recorded (older checks, History off) or when there's no session.
 */
@Composable
fun rememberMessageTime(sessionId: String?): MessageTime? {
    val times = LocalMessageTimes.current
        ?: (LocalContext.current.applicationContext as? App)?.let { app -> MessageTimes { app.timeStore.messageTime(it) } }
    val time by produceState<MessageTime?>(null, sessionId, times) {
        value = if (sessionId == null || times == null) null else times.of(sessionId)
    }
    return time
}

/**
 * One message's time as one quiet line: "Writing 1 min 20 s, reviewing 35 s", or for another
 * app "Writing about 40 s (estimated), reviewing 35 s".
 */
@Composable
fun messageTimeText(time: MessageTime): String {
    val writing = duration(time.writingMs, precise = true)
    val review = duration(time.reviewMs, precise = true)
    return stringResource(
        if (time.writingEstimated) R.string.lab_time_spent_message_estimated else R.string.lab_time_spent_message,
        writing, review,
    )
}

/** [rememberMessageTime] as its line; null when there's nothing to show. */
@Composable
fun messageTimeLine(sessionId: String?): String? = rememberMessageTime(sessionId)?.let { messageTimeText(it) }
