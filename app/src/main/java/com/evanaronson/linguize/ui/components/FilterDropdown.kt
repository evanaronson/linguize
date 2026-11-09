package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R

/**
 * The one way to choose what a page shows: a quiet "Catalan ▾" that opens a menu of the
 * options, the chosen one ticked. Filters, sorts and view choices (a language, a source, a
 * deck, Used or Not yet) use it; buttons are only for actions, so a filter never looks like
 * something that will happen. [description] names what is being chosen ("Source"), and
 * screen readers hear it with the choice ("Source: WhatsApp").
 */
@Composable
fun <T> FilterDropdown(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    description: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn before an option's name in the menu, for example a flag. */
    leading: (@Composable (T) -> Unit)? = null,
    /** What the closed control shows; the selected option's name by default. */
    closed: (@Composable (T) -> Unit)? = null,
    /** Whether the closed control names what it chooses ("Sort: Newest"), for a row of several. */
    labelled: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = label(selected)
    val chosen = if (labelled) "$description: $name" else name
    val spoken = "$description: $name"
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable { open = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    role = Role.DropdownList
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clearAndSetSemantics {}) {
                if (closed != null) closed(selected)
                else Text(chosen, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { open = false; if (!isSelected) onSelect(option) },
                    modifier = Modifier.semantics { this.selected = isSelected },
                    leadingIcon = leading?.let { { it(option) } },
                    trailingIcon = if (isSelected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}
