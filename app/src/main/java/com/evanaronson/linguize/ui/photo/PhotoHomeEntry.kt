package com.evanaronson.linguize.ui.photo

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader

/** Home's "Understand a photo" row: opens the system photo picker and hands back the chosen image. */
@Composable
fun UnderstandPhotoEntry(onPicked: (Uri) -> Unit) {
    val pick = rememberPhotoPicker(onPicked)
    SectionHeader(stringResource(R.string.lab_share_photo_home_header))
    ListCard({
        ListRow(
            stringResource(R.string.lab_share_photo_home_row),
            stringResource(R.string.lab_share_photo_home_row_summary),
            onClick = pick,
            onClickLabel = stringResource(R.string.lab_share_photo_choose_label),
        )
    })
}
