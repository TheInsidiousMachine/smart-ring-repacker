package com.smartring.ui.logic

import com.smartring.core.Connection
import com.smartring.core.ConnectionState
import com.smartring.core.RingEvent
import com.smartring.core.RingStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatClock(
    ms: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (ms == null) return "Not synced yet"
    val fmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", locale)
    return fmt.format(Instant.ofEpochMilli(ms).atZone(zone))
}

fun formatTime(
    ms: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    return fmt.format(Instant.ofEpochMilli(ms).atZone(zone))
}

fun batteryLabel(status: RingStatus): String {
    val pct = status.batteryPercent?.let { "$it%" } ?: "—"
    val charge = when (status.charging) {
        true -> "Charging"
        false -> "Not charging"
        null -> "Charge unknown"
    }
    return "$pct · $charge"
}

fun firmwareLabel(status: RingStatus): String {
    val version = status.firmwareVersion ?: "Unknown version"
    val build = status.customBuild ?: "Stock firmware"
    return "$version · $build"
}

fun eventLabel(event: RingEvent): String = when (event) {
    is RingEvent.Touch -> "Touch ${event.kind.name.lowercase().replace('_', ' ')}"
    is RingEvent.ModeChanged -> "Mode ${event.mode.name.lowercase()}"
    is RingEvent.GestureArmed -> "Gesture armed"
    is RingEvent.GestureFired -> "Gesture fired"
    is RingEvent.HeartBeat -> "Heartbeat ${event.bpm}"
    is RingEvent.Info -> event.text
}

fun statusDump(connection: Connection, status: RingStatus): String = buildString {
    appendLine("link ${connection.state.name.lowercase()}  ${connection.deviceName ?: "—"}")
    appendLine(connection.detail)
    appendLine("battery ${status.batteryPercent ?: "—"}  charging ${status.charging ?: "—"}")
    appendLine("firmware ${status.firmwareVersion ?: "—"}")
    appendLine("build ${status.customBuild ?: "—"}")
    appendLine("mode ${status.mode.name.lowercase()}")
    appendLine("clock ${status.clockSyncedAtMs ?: "—"}")
    appendLine("detector ${status.gestureDetectorOn ?: "—"}  fired ${status.gestureFired}  armed ${status.gestureArmed}")
    appendLine("heart ${status.heartBpm ?: "—"}")
    appendLine("brightness ${status.screenBrightness ?: "—"}  timeout ${status.screenTimeoutSec ?: "—"}s")
}.trimEnd()

data class EventRow(val time: String, val label: String)

fun eventRows(
    events: List<RingEvent>,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    limit: Int = 8,
): List<EventRow> = events.take(limit).map { EventRow(formatTime(it.atMs, zone, locale), eventLabel(it)) }
