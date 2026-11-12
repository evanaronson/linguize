package com.evanaronson.linguize.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lab, lang-selector: the third-party notices that ship in the APK, as Settings › About ›
 * Open-source notices shows them. The language catalog's (Unicode CLDR, circle-flags) and the
 * word lists' (OpenSubtitles frequency lists, CC BY-SA).
 */
object OpenSourceNotices {
    data class Notice(@param:StringRes val title: Int, val asset: String)

    val all = listOf(
        Notice(R.string.lab_lang_selector_title, "lab_languages_NOTICE.txt"),
        Notice(R.string.lab_words_title, "lab_vocab_SOURCES.md"),
    )

    /** Each notice's text, read from the APK's assets; a notice that can't be read is left out. */
    fun read(context: Context): List<Pair<Notice, String>> = all.mapNotNull { notice ->
        runCatching { context.assets.open(notice.asset).bufferedReader().use { it.readText() } }
            .getOrNull()?.let { notice to it.trim() }
    }
}

/** Settings' About group: one quiet row, Open-source notices. */
@Composable
fun AboutSection(onOpenNotices: () -> Unit) {
    SectionHeader(stringResource(R.string.lab_lang_selector_about))
    ListCard({
        ListRow(
            stringResource(R.string.lab_lang_selector_notices),
            stringResource(R.string.lab_lang_selector_notices_summary),
            onClick = onOpenNotices,
        )
    })
}

/** The notices, one card each, as plain selectable text. */
@Composable
fun OpenSourceNoticesScreen() {
    val context = LocalContext.current
    val notices by produceState(emptyList<Pair<OpenSourceNotices.Notice, String>>(), context) {
        value = withContext(Dispatchers.IO) { OpenSourceNotices.read(context.applicationContext) }
    }
    ScreenColumn(Modifier.testTag(NOTICES_TAG)) {
        notices.forEach { (notice, text) ->
            SectionHeader(stringResource(notice.title))
            SectionCard {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(notice.asset))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

internal const val NOTICES_TAG = "lab_lang_selector_notices"
