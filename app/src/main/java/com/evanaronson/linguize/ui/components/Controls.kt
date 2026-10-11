package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R

/** Accessibility overlays cannot attach separate dropdown windows. */
val LocalInlineControls = staticCompositionLocalOf { false }

/** "‹  Back": to the previous screen, or on the card from its understanding page to the suggestions. */
@Composable
fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
        // The arrow only repeats the word, so screen readers hear just "Back".
        Text("‹", Modifier.clearAndSetSemantics {})
        Text(stringResource(R.string.back), Modifier.padding(start = 8.dp))
    }
}

@Composable
fun SectionTitle(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

/** A radio button row; place several inside a `selectableGroup()`. */
@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, selected: T, options: List<T>, optionLabel: (T) -> String, onSelect: (T) -> Unit) {
    val selectedLabel = optionLabel(selected)
    var expanded by remember { mutableStateOf(false) }
    if (LocalInlineControls.current) {
        InlineOptions(label, selectedLabel, expanded, onToggle = { expanded = !expanded }) {
            options.forEach { option ->
                RadioRow(optionLabel(option), selected = option == selected, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
        return
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** A dropdown of checkboxes; the menu stays open while options are toggled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> MultiSelectDropdown(
    label: String,
    options: List<T>,
    selected: Set<T>,
    optionLabel: (T) -> String,
    onToggle: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val separator = stringResource(R.string.list_separator)
    if (LocalInlineControls.current) {
        InlineOptions(label, options.filter { it in selected }.joinToString(separator, transform = optionLabel),
            expanded, onToggle = { expanded = !expanded }) {
            options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().toggleable(value = option in selected, role = Role.Checkbox,
                        onValueChange = { onToggle(option) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = option in selected, onCheckedChange = null)
                    Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.filter { it in selected }.joinToString(separator, transform = optionLabel),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    leadingIcon = { Checkbox(checked = option in selected, onCheckedChange = null) },
                    onClick = { onToggle(option) },
                )
            }
        }
    }
}

@Composable
private fun InlineOptions(label: String, selected: String, expanded: Boolean, onToggle: () -> Unit,
    options: @Composable () -> Unit) {
    Column {
        OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(selected, style = MaterialTheme.typography.bodyLarge)
            }
            Text(if (expanded) "▴" else "▾", Modifier.clearAndSetSemantics {})
        }
        if (expanded) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) { options() }
            }
        }
    }
}
