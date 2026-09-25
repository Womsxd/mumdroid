package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ---- shared building blocks ----

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A read-only dropdown over [entries], rendering [labelOf] for the selected
 * value and for every menu item.
 *
 * The settings screens used to hand-roll this skeleton once per setting, and
 * the only parts that ever differed were the entry list, the field label and
 * how a value reads. Those are the parameters; the shape lives here.
 *
 * [supporting] is the hint some fields carry under the box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> EnumDropdown(
    value: T,
    entries: List<T>,
    label: String,
    onValueChange: (T) -> Unit,
    labelOf: @Composable (T) -> String,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labelOf(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(labelOf(entry)) },
                    onClick = {
                        onValueChange(entry)
                        expanded = false
                    },
                )
            }
        }
    }
    if (supporting != null) {
        Text(
            supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/**
 * A labelled integer slider. The label is the caller's to format, because the
 * settings sliders all put the current value in it — most through a plain
 * string resource, one through a plural. [onValueChange] receives the rounded
 * value, so a caller that needs to clamp does it there.
 */
@Composable
internal fun IntSlider(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Int) -> Unit,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}
