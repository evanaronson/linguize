package com.evanaronson.linguize.ui.history

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.ChatThreadInfo
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.format.DateTimeFormatter

/**
 * Lab Chat in History: each chat conversation shows as one row, where its newest saved message
 * is, so a long conversation doesn't bury the messages sent to people. [threads] maps a saved
 * check's id to its conversation; rows newest first. Other rows are left as they are.
 */
internal fun groupChatThreads(rows: List<RecentRow>, threads: Map<String, ChatThreadInfo>): List<RecentRow> {
    val seen = HashSet<String>()
    return rows.mapNotNull { row ->
        val thread = threads[row.summary.id] ?: return@mapNotNull row
        if (seen.add(thread.conversationId)) row.copy(thread = thread) else null
    }
}

/** The saved checks among [rows] that may be chat messages, to look up their conversations. */
internal fun chatSessionIds(rows: List<RecentRow>): List<String> =
    rows.filter { it.summary.origin == Origin.Chat }.map { it.summary.id }

/** "Chat, Weekend plans" over "5 messages, 2 corrected". */
@Composable
internal fun ThreadRow(row: RecentRow, now: Long, time: DateTimeFormatter, onClick: () -> Unit) {
    val thread = row.thread ?: return
    val resources = LocalResources.current
    val title = thread.title.ifEmpty { thread.persona.orEmpty() }.ifEmpty { stringResource(R.string.lab_chat_untitled) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Linguize.colors.card)
            .clickable(onClickLabel = stringResource(R.string.lab_chat_open_conversation), onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            listOf(relativeDate(resources, row.summary.startedAt, now, time), detectedLanguageLabel(resources, thread.language))
                .joinToString(stringResource(R.string.status_separator)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.lab_chat_history_row, title), style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(threadStatus(resources, thread), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun threadStatus(resources: Resources, thread: ChatThreadInfo): String = listOf(
    resources.getQuantityString(R.plurals.lab_chat_history_messages, thread.messages, thread.messages),
    resources.getQuantityString(R.plurals.lab_chat_history_corrected, thread.corrected, thread.corrected),
).joinToString(resources.getString(R.string.status_separator))
