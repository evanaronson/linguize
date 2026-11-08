package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.RecentSection
import com.evanaronson.linguize.ui.phrasebook.PhrasebookHomeSection
import com.evanaronson.linguize.ui.gaps.GapFillerEntry
import com.evanaronson.linguize.ui.photo.UnderstandPhotoEntry
import com.evanaronson.linguize.ui.patterns.PatternsHomeCard
import com.evanaronson.linguize.ui.patterns.PatternsViewModel

/**
 * The launcher content below the app navigation: check something now, browse previous
 * checks, and set up the accessibility shortcut. Until a key is saved ([needsKey]), a strip
 * at the top leads to it. [historyOn] is null until the setting has been read.
 */
@Composable
fun HomeScreen(
    check: CheckViewModel,
    history: HistoryViewModel,
    historyOn: Boolean?,
    needsKey: Boolean,
    onOpenSettings: () -> Unit,
    onOpen: (id: String) -> Unit,
    onViewHistory: () -> Unit,
    onSetUpAccessibility: () -> Unit,
    onOpenPhrasebook: () -> Unit = {},
    /** Lab: opens Fill the gaps, with Try it's text when it came from its card. */
    onFillGaps: (text: String?) -> Unit = {},
    onHelpReply: () -> Unit = {},
    onPhotoPicked: (android.net.Uri) -> Unit = {},
    patterns: PatternsViewModel? = null,
    onOpenPatterns: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
        ScreenColumn {
            if (needsKey) AddKeyStrip(onOpenSettings)
            SectionHeader(stringResource(R.string.check_your_text))
            SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                TryIt(check, onOpenSettings, onFillGaps)
            }
            GapFillerEntry(onOpen = { onFillGaps(null) })
            UnderstandPhotoEntry(onPhotoPicked)
            com.evanaronson.linguize.ui.writingpad.WritingPadEntry()
            RecentSection(history, historyOn, snackbar, onOpen, onOpenSettings, onViewHistory)
            PhrasebookHomeSection(onOpenPhrasebook)
            com.evanaronson.linguize.ui.reply.ReplyHomeEntry(onHelpReply)
            patterns?.let { PatternsHomeCard(it, onOpenPatterns) }
            SectionHeader(stringResource(R.string.selection_menu_unavailable))
            ListCard({
                ListRow(
                    stringResource(R.string.accessibility_row_title),
                    stringResource(R.string.accessibility_row_summary),
                    onClick = onSetUpAccessibility,
                )
            })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** First run: nothing can be checked until there is a key. */
@Composable
private fun AddKeyStrip(onAddKey: () -> Unit) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_key_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.add_key_description), style = MaterialTheme.typography.bodySmall)
        }
        LinguizeButton(stringResource(R.string.add_key), onAddKey, ButtonKind.Text)
    }
}
