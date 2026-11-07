package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.theme.Linguize

/*
 * App screens follow Android's settings layout: a grey ground, a small muted header above
 * each group, and the group's rows in a borderless white card with hairlines between them.
 * Rows that open a page have no chevron; the page title repeats the row's title.
 */

/** A scrolling app screen on the ground colour. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        content = content,
    )
}

/** The small header above a group; [action] sits at its end (View all). */
@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .padding(start = 20.dp, top = if (action == null) 14.dp else 4.dp, bottom = if (action == null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
    }
}

val SectionCardShape = RoundedCornerShape(24.dp)

/** A group's card. [content] is padded 20dp at the sides, as rows expect. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(SectionCardShape)
            .background(Linguize.colors.card)
            .padding(contentPadding),
        content = content,
    )
}

/** A card of rows with a hairline between each; null rows are skipped. */
@Composable
fun ListCard(vararg rows: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        rows.filterNotNull().forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            row()
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(color = Linguize.colors.rowDivider)
}

/**
 * A row in a card: a title and an optional muted summary, with [trailing] at the end
 * (a text button or a switch). The whole row is the target when [onClick] is set.
 */
@Composable
fun ListRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowText(title, summary, summaryColor, Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
private fun RowText(title: String, summary: String?, summaryColor: Color, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!summary.isNullOrEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = summaryColor)
        }
    }
}

/** A row with an inline switch; the switch is on in the brand colour. */
@Composable
fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowText(title, summary, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, colors = brandSwitchColors())
    }
}

@Composable
fun brandSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Linguize.colors.proof,
    checkedBorderColor = Linguize.colors.proof,
    checkedThumbColor = Color.White,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** One option of a choice page: a radio (or, with [multiple], a checkbox), its name and what it does. */
@Composable
fun ChoiceRow(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    multiple: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (multiple) Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = if (description == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (multiple) Checkbox(checked = selected, onCheckedChange = null)
        else RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        RowText(title, description, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
    }
}

/**
 * A settings page that is one choice: its options in a card, then [note] under it.
 * [description] is null for options that need no explanation.
 */
@Composable
fun <T> ChoicePage(
    options: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String?,
    onSelect: (T) -> Unit,
    note: String? = null,
    multiple: Boolean = false,
) {
    ScreenColumn {
        Spacer(Modifier.height(8.dp))
        SectionCard(Modifier.then(if (multiple) Modifier else Modifier.selectableGroup())) {
            options.forEachIndexed { index, option ->
                if (index > 0) RowDivider()
                ChoiceRow(label(option), description(option), selected(option), { onSelect(option) }, multiple)
            }
        }
        note?.let { CardNote(it) }
    }
}

/** Muted text under a card, aligned with its rows. */
@Composable
fun CardNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
    )
}

/**
 * One item of a card drawn across a lazy list: the card's colour, with the card's top
 * corners on the [first] item and its bottom corners on the [last].
 */
@Composable
fun Modifier.cardSegment(first: Boolean, last: Boolean): Modifier {
    val radius = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp,
    )
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(Linguize.colors.card)
        .padding(start = 20.dp, end = 20.dp, top = if (first) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp)
}
