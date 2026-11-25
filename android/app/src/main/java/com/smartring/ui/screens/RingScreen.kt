package com.smartring.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartring.core.Connection
import com.smartring.core.ConnectionState
import com.smartring.core.RingMode
import com.smartring.core.RingStatus
import com.smartring.ui.components.SectionCard
import com.smartring.ui.components.StatusDot
import com.smartring.ui.components.WideOutlinedButton
import com.smartring.ui.logic.batteryLabel
import com.smartring.ui.logic.firmwareLabel
import kotlin.math.roundToInt

private val modes = listOf(RingMode.CLOCK to "Clock", RingMode.MOUSE to "Mouse", RingMode.HEART to "Heart", RingMode.KEYS to "Keys")
private val timeouts = listOf(3, 5, 10, 15, 30)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RingScreen(
    connection: Connection,
    status: RingStatus,
    clockText: String,
    devMode: Boolean,
    onConnectionClick: () -> Unit,
    onMode: (RingMode) -> Unit,
    onSyncClock: () -> Unit,
    onBrightness: (Int) -> Unit,
    onTimeout: (Int) -> Unit,
    onFind: () -> Unit,
    onDevMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    extras: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionCard(connection, onConnectionClick)
        SectionCard(title = "Ring", modifier = Modifier.fillMaxWidth()) {
            Fact("Battery", batteryLabel(status))
            Fact("Firmware", firmwareLabel(status))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Clock", style = MaterialTheme.typography.labelLarge)
                    Text(clockText, style = MaterialTheme.typography.bodyLarge)
                }
                WideOutlinedButton(text = "Sync now", onClick = onSyncClock)
            }
        }
        SectionCard(title = "Mode", modifier = Modifier.fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = status.mode == mode,
                        onClick = { onMode(mode) },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, modes.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(label)
                    }
                }
            }
            Text(
                "Five taps toggle the mouse. Hold two seconds for the heart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrightnessCard(status.screenBrightness, onBrightness)
        TimeoutCard(status.screenTimeoutSec, onTimeout)
        WideOutlinedButton(text = "Find ring", onClick = onFind, modifier = Modifier.fillMaxWidth())
        SectionCard(title = "Dev mode", modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Console, recorder, and firmware.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = devMode, onCheckedChange = onDevMode)
            }
        }
        extras()
    }
}

@Composable
private fun ConnectionCard(connection: Connection, onClick: () -> Unit) {
    SectionCard(title = connection.deviceName ?: "Ring", modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusDot(connection.state)
            Text(
                connection.detail,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (connection.state == ConnectionState.CONNECTED) {
                WideOutlinedButton(text = "Disconnect", onClick = onClick)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BrightnessCard(level: Int?, onBrightness: (Int) -> Unit) {
    val reported = (level ?: 0).toFloat()
    var slider by remember(level) { mutableFloatStateOf(reported) }
    SectionCard(title = "Brightness", modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onBrightness(slider.roundToInt()) },
            valueRange = 0f..3f,
            steps = 2,
        )
        Text(
            if (level == null) "Not reported yet. 0 is off, 3 is full." else "Level ${slider.roundToInt()} of 3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeoutCard(seconds: Int?, onTimeout: (Int) -> Unit) {
    val options = if (seconds != null && seconds !in timeouts) listOf(seconds) + timeouts else timeouts
    SectionCard(title = "Screen timeout", modifier = Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value ->
                FilterChip(
                    selected = seconds == value,
                    onClick = { onTimeout(value) },
                    label = { Text("${value}s") },
                )
            }
        }
    }
}
