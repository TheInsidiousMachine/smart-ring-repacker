package com.smartring.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartring.core.FirmwareImage
import com.smartring.core.OtaProgress
import com.smartring.ui.logic.FirmwareCatalog
import com.smartring.ui.theme.monoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareTools(
    images: List<FirmwareImage>,
    ota: OtaProgress?,
    onUpdate: (FirmwareImage, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(images) { FirmwareCatalog.ordered(images) }
    var selectedFile by rememberSaveable(ordered.joinToString { it.sha256 }) {
        mutableStateOf(ordered.firstOrNull()?.fileName.orEmpty())
    }
    val selected = ordered.firstOrNull { it.fileName == selectedFile } ?: ordered.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    var confirm by rememberSaveable(selected?.fileName) { mutableStateOf("") }
    val prefix = selected?.sha256?.take(8)?.lowercase().orEmpty()
    val matches = prefix.length == 8 && confirm.trim().lowercase() == prefix

    SectionCard(title = "Firmware", modifier = modifier) {
        Text(
            "Allowlisted images only. Keep the ring on its charger, and still, until it reboots.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected == null) {
            Text("No firmware images.")
        } else {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = FirmwareCatalog.menuLabel(selected),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Image") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ordered.forEach { image ->
                        DropdownMenuItem(
                            text = { Text(FirmwareCatalog.menuLabel(image)) },
                            onClick = {
                                selectedFile = image.fileName
                                confirm = ""
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
            Text(FirmwareCatalog.displayName(selected), style = MaterialTheme.typography.titleSmall)
            Text(selected.fileName, style = monoStyle())
            Text(selected.sha256, style = monoStyle())
            OutlinedTextField(
                value = confirm,
                onValueChange = { next ->
                    confirm = next.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(8)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("type the first 8 characters: $prefix") },
            )
            WideButton(
                text = "Update",
                onClick = { onUpdate(selected, confirm.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = matches,
            )
        }
        ota?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.percent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            val line = progress.error ?: "${progress.stage} · ${progress.percent}%"
            Text(
                line,
                color = if (progress.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun PowerOffButton(
    onPowerOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    WideOutlinedButton(
        text = "Power off the ring",
        onClick = { confirm = true },
        modifier = modifier,
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Power off the ring?") },
            text = { Text("It stays off until you put it on the charger.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onPowerOff() }) { Text("Power off") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun ResetRingButton(
    caution: String,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    WideOutlinedButton(
        text = "Reset ring",
        onClick = { confirm = true },
        modifier = modifier.heightIn(min = 48.dp),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset the ring?") },
            text = { Text(caution) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}
