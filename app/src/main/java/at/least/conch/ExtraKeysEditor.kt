package at.least.conch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Extra-keys row editor: the bottom sheet behind the ⚙ button — pick keys
 * from the [ExtraKeysConfig] pool, save persists the layout. Pure
 * parameterized composable (no activity state), extracted from
 * TerminalActivity for file navigability.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ExtraKeysEditor(current: List<String>, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
    val selected = remember { current.toMutableStateList() }
    ModalBottomSheet(onDismissRequest = onCancel) {
        Text(
            "Extra keys",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            "Tap a key to add or remove it; use the arrows to reorder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SelectedKeyChips(selected)
        AvailableKeyChips(selected)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { onSave(selected.toList()) }) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** The chosen keys, in order: tap a chip to drop it, arrows to reorder. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SelectedKeyChips(selected: SnapshotStateList<String>) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        selected.forEachIndexed { idx, id ->
            androidx.compose.material3.FilterChip(
                selected = true,
                onClick = { selected.remove(id) },
                label = { Text(ExtraKeysConfig.labelFor(id)) }
            )
            IconButton(
                onClick = {
                    if (idx > 0) {
                        val moved = selected.removeAt(idx)
                        selected.add(idx - 1, moved)
                    }
                },
                enabled = idx > 0,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Move ${ExtraKeysConfig.labelFor(id)} left",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = {
                    if (idx < selected.size - 1) {
                        val moved = selected.removeAt(idx)
                        selected.add(idx + 1, moved)
                    }
                },
                enabled = idx < selected.size - 1,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Move ${ExtraKeysConfig.labelFor(id)} right",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The rest of the key pool; tapping one appends it to the row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AvailableKeyChips(selected: SnapshotStateList<String>) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ExtraKeysConfig.ALL.forEach { def ->
            if (def.id !in selected) {
                androidx.compose.material3.FilterChip(
                    selected = false,
                    onClick = { selected.add(def.id) },
                    label = { Text(def.label) }
                )
            }
        }
    }
}

/**
 * One key of the extra-keys row. A tonal button: armed CTRL/ALT read as
 * *selected* (primary container) rather than merely a different blue, and
 * the 48 dp box meets Android's minimum touch target instead of the old
 * 40 dp one.
 */
@Composable
internal fun KeyButton(label: String, armed: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = if (armed) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { if (armed) stateDescription = "Armed" }
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
