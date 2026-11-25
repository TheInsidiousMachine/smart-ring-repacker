package com.smartring.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartring.core.ConnectionState
import com.smartring.core.RecordingState
import com.smartring.ui.logic.EventRow
import com.smartring.ui.theme.monoStyle

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                trailing()
            }
            content()
        }
    }
}

@Composable
fun StatusDot(
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> Color(0xFFE0A100)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(12.dp).clip(CircleShape).background(color))
}

/** App-wide connection. Disconnected always offers Connect. Scanning or connecting is a retry. */
@Composable
fun ConnectionPill(
    state: ConnectionState,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING, ConnectionState.CONNECTING -> "Reconnecting…"
    }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(state)
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            when (state) {
                ConnectionState.DISCONNECTED -> TextButton(onClick = onConnect) { Text("Connect") }
                ConnectionState.SCANNING, ConnectionState.CONNECTING -> TextButton(onClick = onStop) { Text("Stop") }
                ConnectionState.CONNECTED -> Unit
            }
        }
    }
}

@Composable
fun WideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
    ) {
        Text(text)
    }
}

@Composable
fun WideOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
    ) {
        Text(text)
    }
}

private val recordLabels = listOf(
    "target" to "Bump, bump, flick",
    "bumps_only" to "Double bump only",
    "flick_only" to "Flick only",
    "everyday" to "Everyday motion",
)

@Composable
fun DevPanel(
    dump: String,
    recording: RecordingState?,
    recordLabel: String,
    events: List<EventRow>,
    onRecordLabel: (String) -> Unit,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = if (recording == null) "Developer" else "Recording ${recording.label}",
        modifier = modifier,
        trailing = {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        },
    ) {
        if (recording != null) {
            Text(
                "${recording.samples} samples · ${"%.0f".format(recording.samplesPerSec)}/s · gaps ${recording.gaps}",
                style = monoStyle(),
            )
        }
        if (!open) return@SectionCard
        Text(dump, style = monoStyle())
        Text("Recorder", style = MaterialTheme.typography.titleSmall)
        recordLabels.forEach { (id, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = recordLabel == id,
                        enabled = recording == null,
                        onClick = { onRecordLabel(id) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = recordLabel == id,
                    onClick = { onRecordLabel(id) },
                    enabled = recording == null,
                )
                Text(label)
            }
        }
        Text(
            "About 10 reps, with a short pause between them. The fast sensor stays on for the whole take.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WideButton(
            text = if (recording == null) "Start recording" else "Stop recording",
            onClick = onToggleRecording,
            modifier = Modifier.fillMaxWidth(),
        )
        recording?.let {
            Text(it.file, style = monoStyle())
        }
        Text("Recent events", style = MaterialTheme.typography.titleSmall)
        if (events.isEmpty()) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.forEach { row ->
                Text("${row.time}  ${row.label}", style = monoStyle())
            }
        }
    }
}
