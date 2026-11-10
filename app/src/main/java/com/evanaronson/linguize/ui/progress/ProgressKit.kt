package com.evanaronson.linguize.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.language.scopeLanguageName

/*
 * Lab, Progress IA: the pieces every page Progress opens is built from, so Words, Your
 * conjugations, Your patterns, Practice and Time spent read as one area. A page starts with
 * its language ([ScopeHeader]) and a summary card of [StatTile]s, then its detail, and ends
 * with [HowItsCounted]. Nothing yet is an [EmptyCard]; reading is [PageLoading].
 */

/** The padding of a card that holds text rather than rows. */
val ProgressCardPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/**
 * A page's first header: the language it shows ("Catalan"), or "Auto-detect" under
 * Linguize. Pages opened from a tab have no selector, so the page says it.
 */
@Composable
fun ScopeHeader(language: String?) {
    SectionHeader(language?.let { scopeLanguageName(it) } ?: stringResource(R.string.lab_ia_progress_all_languages))
}

/** A figure and what it counts: "312" over "words". Read as one ("312 words"). */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Quiet(label)
    }
}

/** Tiles side by side, each an equal share of the width. */
@Composable
fun StatRow(vararg tiles: Pair<String, String>) {
    Row(Modifier.fillMaxWidth()) {
        tiles.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f)) }
    }
}

/** Nothing to show yet: what's missing, and how it fills. */
@Composable
fun EmptyCard(title: String, hint: String?) {
    Spacer(Modifier.height(8.dp))
    SectionCard(contentPadding = ProgressCardPadding) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        hint?.let { Quiet(it, Modifier.padding(top = 2.dp)) }
    }
}

/** Reading: a small spinner and what is being read. */
@Composable
fun PageLoading(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics { }, strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurface)
        Quiet(label)
    }
}

/** The last section of a page: how its numbers are made, one short paragraph each. */
@Composable
fun HowItsCounted(paragraphs: List<String>) {
    SectionHeader(stringResource(R.string.lab_ia_progress_how))
    SectionCard(contentPadding = ProgressCardPadding) {
        paragraphs.forEachIndexed { index, text ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Quiet(text)
        }
    }
}

/** A chart's key: a dot of the series' colour and its name. The dot is decoration. */
@Composable
fun ChartLegend(color: Color, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape).clearAndSetSemantics { })
        Spacer(Modifier.width(8.dp))
        Quiet(text)
    }
}
