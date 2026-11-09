package com.evanaronson.linguize.ui.home

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
import androidx.compose.ui.platform.LocalContext
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
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.writingpad.writingPadIntent

/**
 * Write, one of the four destinations: check something now (Try it), then the tools for a
 * specific piece of writing. Until a key is saved ([needsKey]), a strip at the
 * top leads to it. Progress, History and Settings have places of their own.
 */
@Composable
internal fun WriteScreen(
    check: CheckViewModel,
    needsKey: Boolean,
    onOpenSettings: () -> Unit,
    /** Opens Fill the gaps, with Try it's text when it came from its card. */
    onFillGaps: (text: String?) -> Unit,
) {
    val context = LocalContext.current
    ScreenColumn {
        if (needsKey) AddKeyStrip(onOpenSettings)
        SectionHeader(stringResource(R.string.check_your_text))
        SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            TryIt(check, onOpenSettings, onFillGaps)
        }

        SectionHeader(stringResource(R.string.lab_home_ia_more_ways))
        ListCard(
            {
                ListRow(
                    stringResource(R.string.lab_writing_pad_title),
                    stringResource(R.string.lab_writing_pad_home_summary),
                    onClick = { context.openExternal(writingPadIntent(context)) },
                    onClickLabel = stringResource(R.string.lab_writing_pad_open),
                )
            },
            {
                ListRow(
                    stringResource(R.string.lab_gap_filler_title),
                    stringResource(R.string.lab_gap_filler_home_summary),
                    onClick = { onFillGaps(null) },
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
