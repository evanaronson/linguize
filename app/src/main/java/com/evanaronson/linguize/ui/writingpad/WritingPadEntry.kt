package com.evanaronson.linguize.ui.writingpad

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.openExternal

/** Home's way into the Writing pad: its own header and row. */
@Composable
fun WritingPadEntry() {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.lab_writing_pad_title))
    ListCard({
        ListRow(
            stringResource(R.string.lab_writing_pad_title),
            stringResource(R.string.lab_writing_pad_home_summary),
            onClick = { context.openExternal(writingPadIntent(context)) },
            onClickLabel = stringResource(R.string.lab_writing_pad_open),
        )
    })
}

/**
 * Opens the pad's activity. It is an entry point, so it lives in `menu`, which `ui` must not
 * import; the class is named here instead, and a test holds the name to the manifest.
 */
fun writingPadIntent(context: Context): Intent = Intent().setClassName(context, WRITING_PAD_ACTIVITY)

internal const val WRITING_PAD_ACTIVITY = "com.evanaronson.linguize.menu.WritingPadActivity"
