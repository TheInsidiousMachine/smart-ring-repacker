package com.smartring.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.smartring.core.LogLine
import com.smartring.ui.logic.DevCommand
import com.smartring.ui.logic.DevCommands
import com.smartring.ui.logic.formatTime
import com.smartring.ui.theme.monoStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConsoleSheet(
    lines: List<LogLine>,
    filter: String,
    onFilter: (String) -> Unit,
    onClear: () -> Unit,
    onSend: (String) -> String?,
    onPreset: (DevCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var raw by remember { mutableStateOf("") }
    var rawError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<DevCommand?>(null) }
    var stock by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Console", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Filter") },
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(lines, key = { index, line -> "$index-${line.atMs}-${line.text.hashCode()}" }) { _, line ->
                    Text("${formatTime(line.atMs)}  ${line.text}", style = monoStyle())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = {
                    val text = lines.joinToString("\n") { "${formatTime(it.atMs)}  ${it.text}" }
                    clipboard.setText(AnnotatedString(text))
                }) { Text("Copy") }
                Text(
                    "${lines.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Raw command") },
                placeholder = { Text("02 04 10") },
                isError = rawError != null,
                supportingText = { rawError?.let { Text(it) } },
            )
            WideButton(
                text = "Send raw",
                onClick = {
                    rawError = onSend(raw)
                    if (rawError == null) raw = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = raw.isNotBlank(),
            )
            TextButton(onClick = { stock = !stock }) { Text(if (stock) "Hide stock commands" else "Stock commands") }
            if (stock) {
                DevCommands.groups.forEach { group ->
                    Text(group, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevCommands.all.filter { it.group == group }.forEach { command ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    if (command.caution == null) onPreset(command) else pending = command
                                },
                                label = { Text(command.label) },
                            )
                        }
                    }
                }
            }
        }
    }
    pending?.let { command ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(command.label) },
            text = { Text(command.caution.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { pending = null; onPreset(command) }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
