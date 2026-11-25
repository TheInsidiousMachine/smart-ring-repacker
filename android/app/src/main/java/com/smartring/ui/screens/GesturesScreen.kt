package com.smartring.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.smartring.core.ApiSettings
import com.smartring.core.ApiStatus
import com.smartring.core.CustomGesture
import com.smartring.core.GestureAction
import com.smartring.core.TrainingState
import com.smartring.ui.components.SectionCard
import com.smartring.ui.components.WideButton
import com.smartring.ui.components.WideOutlinedButton
import com.smartring.ui.logic.ActionKind
import com.smartring.ui.logic.ApiReference
import com.smartring.ui.logic.MEDIA_KEYS
import com.smartring.ui.logic.formatHeaders
import com.smartring.ui.logic.freshAction
import com.smartring.ui.logic.kind
import com.smartring.ui.logic.parseHeaders
import com.smartring.ui.logic.summary
import com.smartring.ui.theme.monoStyle
import com.smartring.ui.vm.ActivityLine
import kotlin.math.roundToInt

@Composable
fun GesturesScreen(
    gestures: List<CustomGesture>,
    listening: Boolean,
    training: TrainingState?,
    feed: List<ActivityLine>,
    feedTimes: List<String>,
    apiSettings: ApiSettings,
    apiStatus: ApiStatus,
    onListening: (Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onStrictness: (String, Int) -> Unit,
    onAction: (String, GestureAction) -> Unit,
    onTest: (String) -> Unit,
    onTrain: (String) -> Unit,
    onStopTraining: () -> Unit,
    onClearExamples: (String) -> Unit,
    onApi: (ApiSettings) -> String?,
    onRegenerateToken: () -> Unit,
    modifier: Modifier = Modifier,
    extras: @Composable () -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Listening", modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Match trained gestures. The ring streams motion to the phone.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = listening, onCheckedChange = onListening)
            }
        }
        training?.let { state ->
            val title = gestures.firstOrNull { it.id == state.gestureId }?.name ?: "Gesture"
            TrainingCard(title, state, onStopTraining)
        }
        SectionCard(title = "New gesture", modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
            )
            WideButton(
                text = "Add",
                onClick = {
                    onCreate(name)
                    name = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(),
            )
        }
        gestures.forEach { gesture ->
            GestureCard(
                gesture = gesture,
                trainingThis = training?.gestureId == gesture.id,
                onRename = { onRename(gesture.id, it) },
                onDelete = { onDelete(gesture.id) },
                onEnabled = { onEnabled(gesture.id, it) },
                onStrictness = { onStrictness(gesture.id, it) },
                onAction = { onAction(gesture.id, it) },
                onTest = { onTest(gesture.id) },
                onTrain = { onTrain(gesture.id) },
                onStopTraining = onStopTraining,
                onClearExamples = { onClearExamples(gesture.id) },
            )
        }
        if (gestures.isEmpty()) {
            Text(
                "No gestures yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FeedCard(feed, feedTimes)
        ApiCard(apiSettings, apiStatus, onApi, onRegenerateToken)
        extras()
    }
}

@Composable
private fun TrainingCard(title: String, state: TrainingState, onStop: () -> Unit) {
    val fraction = if (state.examplesWanted <= 0) 0f else {
        state.examplesRecorded.toFloat() / state.examplesWanted.toFloat()
    }
    SectionCard(title = title, modifier = Modifier.fillMaxWidth()) {
        Text(state.prompt, style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${state.examplesRecorded} of ${state.examplesWanted}", style = MaterialTheme.typography.bodyLarge)
        WideOutlinedButton(text = "Stop training", onClick = onStop, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GestureCard(
    gesture: CustomGesture,
    trainingThis: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onStrictness: (Int) -> Unit,
    onAction: (GestureAction) -> Unit,
    onTest: () -> Unit,
    onTrain: () -> Unit,
    onStopTraining: () -> Unit,
    onClearExamples: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var strictness by remember(gesture.strictness) { mutableStateOf(gesture.strictness.toFloat()) }
    SectionCard(title = gesture.name, modifier = Modifier.fillMaxWidth(), trailing = {
        Switch(checked = gesture.enabled, onCheckedChange = onEnabled)
    }) {
        val need = if (gesture.examples < 3) " · needs 3 to recognize" else ""
        Text("${gesture.examples} examples$need", style = MaterialTheme.typography.bodyMedium)
        Text(gesture.action.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Strictness ${strictness.roundToInt()}", style = MaterialTheme.typography.labelLarge)
        androidx.compose.material3.Slider(
            value = strictness,
            onValueChange = { strictness = it },
            onValueChangeFinished = { onStrictness(strictness.roundToInt()) },
            valueRange = 0f..100f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WideButton(
                text = if (trainingThis) "Stop" else "Train",
                onClick = { if (trainingThis) onStopTraining() else onTrain() },
                modifier = Modifier.weight(1f),
            )
            WideOutlinedButton(text = "Rename", onClick = { renaming = true }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { clearing = true }, modifier = Modifier.weight(1f)) { Text("Clear examples") }
            TextButton(onClick = { deleting = true }, modifier = Modifier.weight(1f)) { Text("Delete") }
        }
        ActionEditor(gesture.action, onAction, onTest)
    }
    if (renaming) NameDialog(gesture.name, onDismiss = { renaming = false }, onSave = { onRename(it); renaming = false })
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${gesture.name}?") },
            text = { Text("Its examples go with it.") },
            confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear examples?") },
            text = { Text("${gesture.name} will need training again.") },
            confirmButton = { TextButton(onClick = { clearing = false; onClearExamples() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionEditor(
    saved: GestureAction,
    onSave: (GestureAction) -> Unit,
    onTest: () -> Unit,
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    val dirty = draft != saved
    Text("Action", style = MaterialTheme.typography.titleSmall)
    KindMenu(draft.kind()) { draft = freshAction(it, draft) }
    when (val action = draft) {
        GestureAction.None -> Text(
            "Recognized, and nothing else happens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is GestureAction.Webhook -> WebhookFields(action, saved) { draft = it }
        is GestureAction.RingText -> OutlinedTextField(
            value = action.text,
            onValueChange = { draft = action.copy(text = it.take(80)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Text") },
        )
        is GestureAction.PcKeys -> OutlinedTextField(
            value = action.keys,
            onValueChange = { draft = action.copy(keys = it.take(80)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Keys") },
            placeholder = { Text("ctrl+c") },
        )
        is GestureAction.MediaKey -> MediaMenu(action.key) { draft = action.copy(key = it) }
        GestureAction.ApiEventOnly -> Text(
            "Published on the API event stream only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        WideButton(
            text = if (dirty) "Save action" else "Saved",
            onClick = { onSave(draft) },
            modifier = Modifier.weight(1f),
            enabled = dirty,
        )
        WideOutlinedButton(
            text = "Test",
            onClick = {
                if (dirty) onSave(draft)
                onTest()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun KindMenu(kind: ActionKind, onKind: (ActionKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        WideOutlinedButton(text = kind.label, onClick = { open = true }, modifier = Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionKind.entries.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = { onKind(item); open = false })
            }
        }
    }
}

@Composable
private fun MediaMenu(key: String, onKey: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        WideOutlinedButton(
            text = key.replace('_', ' '),
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MEDIA_KEYS.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.replace('_', ' ')) },
                    onClick = { onKey(item); open = false },
                )
            }
        }
    }
}

@Composable
private fun WebhookFields(
    action: GestureAction.Webhook,
    resetKey: Any,
    onChange: (GestureAction.Webhook) -> Unit,
) {
    var headers by remember(resetKey) { mutableStateOf(formatHeaders(action.headers)) }
    OutlinedTextField(
        value = action.url,
        onValueChange = { onChange(action.copy(url = it.take(200))) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("URL") },
    )
    OutlinedTextField(
        value = action.method,
        onValueChange = { onChange(action.copy(method = it.trim().take(8).uppercase())) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Method") },
    )
    OutlinedTextField(
        value = action.body,
        onValueChange = { onChange(action.copy(body = it.take(500))) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text("Body") },
    )
    OutlinedTextField(
        value = headers,
        onValueChange = {
            headers = it
            onChange(action.copy(headers = parseHeaders(it)))
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text("Headers, one Name: value per line") },
    )
}

@Composable
private fun FeedCard(feed: List<ActivityLine>, times: List<String>) {
    SectionCard(title = "Recent", modifier = Modifier.fillMaxWidth()) {
        if (feed.isEmpty()) {
            Text(
                "Hits and action results show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            feed.forEachIndexed { index, line ->
                val time = times.getOrElse(index) { "" }
                val color = when (line.ok) {
                    false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text("$time  ${line.title}", style = MaterialTheme.typography.titleSmall, color = color)
                Text(line.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ApiCard(
    settings: ApiSettings,
    status: ApiStatus,
    onApi: (ApiSettings) -> String?,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    var confirmToken by remember { mutableStateOf(false) }
    SectionCard(title = "API", modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = settings.enabled,
                onCheckedChange = { enabled ->
                    portError = onApi(settings.copy(enabled = enabled, port = port.toIntOrNull() ?: settings.port))
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LAN", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = settings.lan, onCheckedChange = { onApi(settings.copy(lan = it)) })
        }
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Port") },
            isError = portError != null,
            supportingText = { portError?.let { Text(it) } },
        )
        WideOutlinedButton(
            text = "Set port",
            onClick = { portError = onApi(settings.copy(port = port.toIntOrNull() ?: -1)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Token", style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
            Text(
                text = when {
                    settings.token.isEmpty() -> "Generated the first time you enable the API."
                    reveal -> settings.token
                    else -> "•••• ${settings.token.takeLast(4)}"
                },
                style = monoStyle(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { reveal = !reveal }, modifier = Modifier.weight(1f)) {
                Text(if (reveal) "Hide" else "Show")
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(settings.token)) },
                enabled = settings.token.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Copy") }
            TextButton(onClick = { confirmToken = true }, modifier = Modifier.weight(1f)) { Text("New") }
        }
        Text(
            if (status.running) "Running · ${status.clients} clients" else "Stopped",
            style = MaterialTheme.typography.bodyMedium,
        )
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        status.urls.forEach { url ->
            SelectionContainer { Text(url, style = monoStyle()) }
        }
        Text(ApiReference.PATH, style = monoStyle())
        Text(ApiReference.BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmToken) {
        AlertDialog(
            onDismissRequest = { confirmToken = false },
            title = { Text("Replace the token?") },
            text = { Text("Clients using the current token will need the new one.") },
            confirmButton = { TextButton(onClick = { confirmToken = false; onRegenerate() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmToken = false }) { Text("Cancel") } },
        )
    }
}
