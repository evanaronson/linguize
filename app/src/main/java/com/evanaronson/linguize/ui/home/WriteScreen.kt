package com.evanaronson.linguize.ui.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.evanaronson.linguize.ui.photo.rememberPhotoPicker
import com.evanaronson.linguize.ui.write.WriteDraftList
import com.evanaronson.linguize.ui.write.WriteEditor
import com.evanaronson.linguize.ui.write.rememberWriteViewModel

/**
 * Write, one of the four destinations: an editor with drafts, checked with the card, then
 * the tools for a specific piece of writing or reading. Until a key is saved ([needsKey]), a strip at the
 * top leads to it. Progress, History and Settings have places of their own.
 */
@Composable
internal fun WriteScreen(
    check: CheckViewModel,
    needsKey: Boolean,
    onOpenSettings: () -> Unit,
    /** Opens Fill the gaps, with the editor's text when it came from its card. */
    onFillGaps: (text: String?) -> Unit,
    onHelpReply: () -> Unit,
    onPhotoPicked: (Uri) -> Unit,
) {
    val write = rememberWriteViewModel()
    val pickPhoto = rememberPhotoPicker(onPhotoPicked)
    ScreenColumn {
        if (needsKey) AddKeyStrip(onOpenSettings)
        SectionHeader(stringResource(R.string.lab_write_header))
        SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            WriteEditor(write, check, onOpenSettings, onFillGaps)
        }
        WriteDraftList(write, onOpened = { check.dismiss(applied = false) })

        SectionHeader(stringResource(R.string.lab_home_ia_more_ways))
        ListCard(
            {
                ListRow(
                    stringResource(R.string.lab_gap_filler_title),
                    stringResource(R.string.lab_gap_filler_home_summary),
                    onClick = { onFillGaps(null) },
                )
            },
        )

        SectionHeader(stringResource(R.string.lab_home_ia_read_and_reply))
        ListCard(
            {
                ListRow(
                    stringResource(R.string.lab_reply_drafter_help_me_reply),
                    stringResource(R.string.lab_reply_drafter_home_summary),
                    onClick = onHelpReply,
                )
            },
            {
                ListRow(
                    stringResource(R.string.lab_share_photo_home_row),
                    stringResource(R.string.lab_share_photo_home_row_summary),
                    onClick = pickPhoto,
                    onClickLabel = stringResource(R.string.lab_share_photo_choose_label),
                )
            },
        )
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
