package com.smartring.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartring.ui.logic.SegmentFont
import kotlinx.coroutines.delay

/** Five-character window, the same width the ring can hold at once. */
@Composable
fun RingScreenFace(
    text: String,
    modifier: Modifier = Modifier,
) {
    val lit = MaterialTheme.colorScheme.primary
    val shown = text.padEnd(5).take(5)
    Canvas(
        modifier
            .aspectRatio(2.7f)
            .semantics { contentDescription = "Ring screen $shown" },
    ) {
        val bezel = Color(0xFF120C0C)
        val window = Color(0xFF1A0E0E)
        drawRoundRect(bezel, cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()))
        val inset = 8.dp.toPx()
        drawRoundRect(
            color = window,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
        )
        val pad = 16.dp.toPx()
        val innerW = size.width - pad * 2
        val innerH = size.height - pad * 2
        val dot = minOf(innerW / 32.6f, innerH / 8.4f)
        val dotGap = dot * 0.22f
        val cellGap = dot * 0.9f
        val glyphW = dot * 5 + dotGap * 4
        val glyphH = dot * 7 + dotGap * 6
        val totalW = glyphW * 5 + cellGap * 4
        var originX = (size.width - totalW) / 2f
        val originY = (size.height - glyphH) / 2f
        val dim = lit.copy(alpha = 0.16f)
        for (index in 0 until 5) {
            val ch = shown[index]
            for (y in 0 until 7) {
                for (x in 0 until 5) {
                    drawCircle(
                        color = if (SegmentFont.isLit(ch, x, y)) lit else dim,
                        radius = dot / 2f,
                        center = Offset(
                            originX + x * (dot + dotGap) + dot / 2f,
                            originY + y * (dot + dotGap) + dot / 2f,
                        ),
                    )
                }
            }
            originX += glyphW + cellGap
        }
    }
}

@Composable
fun AnimatedRingFace(
    frames: List<String>,
    stepMs: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val safe = frames.ifEmpty { listOf("     ") }
    var index by remember(safe, stepMs) { mutableIntStateOf(0) }
    LaunchedEffect(safe, stepMs, animate) {
        index = 0
        if (!animate || safe.size <= 1) return@LaunchedEffect
        while (true) {
            delay(stepMs.coerceIn(60, 1500).toLong())
            index = (index + 1) % safe.size
        }
    }
    val frame = safe[index.coerceIn(0, safe.lastIndex)]
    Column(modifier) {
        RingScreenFace(frame, Modifier.fillMaxWidth())
        Text(
            text = "${index + 1} / ${safe.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
