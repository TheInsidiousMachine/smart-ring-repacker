package com.smartring.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.smartring.core.GestureMessage
import com.smartring.core.RingStatus
import com.smartring.ui.components.AnimatedRingFace
import com.smartring.ui.components.SectionCard
import com.smartring.ui.components.WideButton
import com.smartring.ui.components.WideOutlinedButton
import com.smartring.ui.logic.MessagePace
import com.smartring.ui.logic.MessagePreview
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(
    message: GestureMessage,
    status: RingStatus,
    saved: List<String>,
    lastGesture: String?,
    onMessage: (GestureMessage) -> Unit,
    onPlay: () -> Unit,
    onDetector: (Boolean) -> Unit,
    onShowNow: (String) -> Unit,
    onSave: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    extras: @Composable () -> Unit = {},
) {
    var draft by remember(message) { mutableStateOf(message) }
    var showNow by remember { mutableStateOf("") }
    val frames = MessagePreview.played(draft)
    val once = MessagePreview.previewFrames(draft.text, draft.scroll)
    val seconds = once.size * draft.msPerStep.coerceIn(60, 1500) * draft.plays.coerceIn(1, 5) / 1000.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Bump message", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Two bumps, then a flick, play this.",
                style = MaterialTheme.typography.bodyMedium,
            )
            AnimatedRingFace(
                frames = frames,
                stepMs = draft.msPerStep,
                animate = animate,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${once.size} steps · ${"%.1f".format(seconds)} s · ${draft.plays} plays",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (MessagePreview.capped(draft.text, draft.scroll)) {
                Text("Cut at 32 steps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = draft.text,
                onValueChange = { draft = draft.copy(text = it.take(80)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Bump message") },
            )
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bumpPresets.forEach { preset ->
                    FilterChip(
                        selected = draft == preset.message,
                        onClick = {
                            draft = preset.message
                            onMessage(preset.message)
                        },
                        label = { Text(preset.label) },
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = draft.scroll,
                    onClick = { draft = draft.copy(scroll = true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Scroll") }
                SegmentedButton(
                    selected = !draft.scroll,
                    onClick = { draft = draft.copy(scroll = false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Words") }
            }
            Text(
                if (draft.scroll) "Scrolls one character at a time." else "Each word, centered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Speed · ${MessagePace.label(draft.msPerStep)}", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MessagePace.presets.forEach { (name, ms) ->
                    FilterChip(
                        selected = draft.msPerStep == ms,
                        onClick = { draft = draft.copy(msPerStep = ms) },
                        label = { Text(name) },
                    )
                }
            }
            Slider(
                value = draft.msPerStep.toFloat(),
                onValueChange = { draft = draft.copy(msPerStep = it.roundToInt()) },
                valueRange = 60f..1500f,
            )
            Text("Plays ${draft.plays}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = draft.plays.toFloat(),
                onValueChange = { draft = draft.copy(plays = it.roundToInt()) },
                valueRange = 1f..5f,
                steps = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WideButton(
                    text = "Send",
                    onClick = { onMessage(draft) },
                    modifier = Modifier.weight(1f),
                )
                WideOutlinedButton(
                    text = "Play now",
                    onClick = {
                        if (draft != message) onMessage(draft)
                        onPlay()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Detector", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = status.gestureDetectorOn == true, onCheckedChange = onDetector)
            }
            Text(
                "Fired ${status.gestureFired} · Armed ${status.gestureArmed}",
                style = MaterialTheme.typography.bodyMedium,
            )
            lastGesture?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        SectionCard(title = "Show now", modifier = Modifier.fillMaxWidth()) {
            AnimatedRingFace(
                frames = MessagePreview.showNow(showNow),
                stepMs = 180,
                animate = animate && showNow.trim().length > 5,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = showNow,
                onValueChange = { showNow = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Text for the ring") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WideButton(text = "Show now", onClick = { onShowNow(showNow) }, modifier = Modifier.weight(1f))
                WideOutlinedButton(text = "Save", onClick = { onSave(showNow) }, modifier = Modifier.weight(1f))
            }
            if (saved.isEmpty()) {
                Text(
                    "Saved messages show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tap to show. Long-press to delete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved.forEach { msg ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.combinedClickable(
                                onClick = { onShowNow(msg) },
                                onLongClick = { onDeleteSaved(msg) },
                            ),
                        ) {
                            Text(msg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            }
        }
        extras()
    }
}

private data class BumpPreset(val label: String, val message: GestureMessage)

private val bumpPresets = listOf(
    BumpPreset("HI THERE", GestureMessage("HI THERE", scroll = true, msPerStep = MessagePace.FAST, plays = 1)),
    BumpPreset("HELLO", GestureMessage("HELLO", scroll = true, msPerStep = MessagePace.FAST, plays = 1)),
    BumpPreset("OK", GestureMessage("OK", scroll = false, msPerStep = MessagePace.NORMAL, plays = 1)),
)
