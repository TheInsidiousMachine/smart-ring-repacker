package com.smartring.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.smartring.core.MouseSettings
import com.smartring.core.MouseStyle
import com.smartring.ui.components.SectionCard
import com.smartring.ui.components.WideOutlinedButton
import com.smartring.ui.logic.MouseCurve
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MouseScreen(
    settings: MouseSettings,
    onChange: (MouseSettings) -> Unit,
    onRecenter: () -> Unit = {},
    modifier: Modifier = Modifier,
    extras: @Composable () -> Unit = {},
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    val pointer = draft.style == MouseStyle.POINTER

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Mouse", modifier = Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = pointer,
                    onClick = {
                        draft = draft.copy(style = MouseStyle.POINTER)
                        onChange(draft)
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Pointer") }
                SegmentedButton(
                    selected = !pointer,
                    onClick = {
                        draft = draft.copy(style = MouseStyle.JOYSTICK)
                        onChange(draft)
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Joystick") }
            }
            Text(
                if (pointer) {
                    "The cursor follows your hand and stops when you stop."
                } else {
                    "Hold a tilt and the cursor keeps moving."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard(title = "On the ring", modifier = Modifier.fillMaxWidth()) {
            Text("Five taps turn the mouse on or off.", style = MaterialTheme.typography.bodyLarge)
            if (pointer) {
                Text(
                    "Roll the wrist for left and right. Tilt the hand up and down.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                "Tap to click. Press and move to drag. Press and hold still 0.7 s to right-click.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Wrong direction? Use Invert / Swap.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SectionCard(title = "Feel", modifier = Modifier.fillMaxWidth()) {
            LabeledSlider(
                label = "Sensitivity ${draft.speed}",
                value = draft.speed.toFloat(),
                range = 1f..100f,
                onChange = { draft = draft.copy(speed = it.roundToInt().coerceIn(1, 100)) },
                onFinish = { onChange(draft) },
            )
            LabeledSlider(
                label = "Acceleration ${draft.curve}",
                value = draft.curve.toFloat(),
                range = 0f..100f,
                onChange = { draft = draft.copy(curve = it.roundToInt().coerceIn(0, 100)) },
                onFinish = { onChange(draft) },
            )
            if (!pointer) {
                LabeledSlider(
                    label = "Deadzone ${draft.deadzoneDeg}°",
                    value = draft.deadzoneDeg.toFloat(),
                    range = 0f..15f,
                    onChange = { draft = draft.copy(deadzoneDeg = it.roundToInt().coerceIn(0, 15)) },
                    onFinish = { onChange(draft) },
                )
            }
            LabeledSlider(
                label = "Right-click hold ${draft.rightClickHoldMs} ms",
                value = draft.rightClickHoldMs.toFloat(),
                range = 200f..1500f,
                onChange = { draft = draft.copy(rightClickHoldMs = it.roundToInt().coerceIn(200, 1500)) },
                onFinish = { onChange(draft) },
            )
            ToggleRow("Invert X", draft.invertX) {
                draft = draft.copy(invertX = it)
                onChange(draft)
            }
            ToggleRow("Invert Y", draft.invertY) {
                draft = draft.copy(invertY = it)
                onChange(draft)
            }
            ToggleRow("Swap axes", draft.swapAxes) {
                draft = draft.copy(swapAxes = it)
                onChange(draft)
            }
            if (!pointer) {
                WideOutlinedButton(
                    text = "Recenter",
                    onClick = onRecenter,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CurveGraph(
                style = draft.style,
                curve = draft.curve,
                sensitivity = draft.speed,
                deadzoneDeg = draft.deadzoneDeg,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Text(
                if (pointer) {
                    "Cursor gain against how fast the hand moves."
                } else {
                    "Cursor speed against tilt. The flat middle is the deadzone."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        extras()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = onChange, onValueChangeFinished = onFinish, valueRange = range)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CurveGraph(
    style: MouseStyle,
    curve: Int,
    sensitivity: Int,
    deadzoneDeg: Int,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val pointer = style == MouseStyle.POINTER
    Canvas(modifier) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 10.dp.toPx()
        val path = Path()
        if (pointer) {
            drawLine(axis, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
            MouseCurve.pointerSamples(curve, sensitivity).forEachIndexed { index, (speed, gain) ->
                val x = left + speed * (right - left)
                val y = bottom - gain.coerceIn(0f, 1f) * (bottom - top) * 0.92f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        } else {
            val mid = (top + bottom) / 2f
            drawLine(axis, Offset(left, mid), Offset(right, mid), strokeWidth = 1.dp.toPx())
            MouseCurve.samples(curve, deadzoneDeg).forEachIndexed { index, (tilt, response) ->
                val x = left + (tilt + 1f) / 2f * (right - left)
                val y = mid - response * (bottom - top) / 2f * 0.92f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
