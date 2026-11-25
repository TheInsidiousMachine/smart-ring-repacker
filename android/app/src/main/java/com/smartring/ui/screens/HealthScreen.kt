package com.smartring.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartring.core.RingMode
import com.smartring.ui.components.SectionCard

@Composable
fun HealthScreen(
    mode: RingMode,
    bpm: Int?,
    pulse: Int,
    onHeart: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    extras: @Composable () -> Unit = {},
) {
    val on = mode == RingMode.HEART
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        scale.snapTo(1.14f)
        scale.animateTo(1f, tween(durationMillis = 520))
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Heart", modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp).scale(scale.value),
                    )
                    Text(
                        text = bpm?.toString() ?: "—",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (bpm == null) "Waiting for a beat" else "beats per minute",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Heart mode", style = MaterialTheme.typography.labelLarge)
                    Switch(checked = on, onCheckedChange = onHeart)
                }
            }
            Text(
                "Snug and still. Off returns to the clock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        extras()
    }
}
