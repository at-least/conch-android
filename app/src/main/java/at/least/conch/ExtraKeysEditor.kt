package at.least.conch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            "Tap to add or remove. Long-press ⚙ row keys later to reorder (drag support coming).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        // selected chips (tap to remove)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            selected.forEach { id ->
                androidx.compose.material3.FilterChip(
                    selected = true,
                    onClick = { selected.remove(id) },
                    label = { Text(ExtraKeysConfig.labelFor(id)) }
                )
            }
        }
        // available chips (tap to append)
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

/** One key of the extra-keys row. */
@Composable
internal fun KeyButton(label: String, armed: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (armed) Color(0xFF2196F3) else Color(0xFF263238),
            contentColor = Color(0xFFE0E0E0)
        ),
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(40.dp)
            .defaultMinSize(minWidth = 48.dp)
    ) {
        Text(label, fontSize = 13.sp)
    }
}
