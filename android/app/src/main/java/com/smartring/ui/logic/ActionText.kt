package com.smartring.ui.logic

import com.smartring.core.GestureAction

enum class ActionKind(val label: String) {
    None("Nothing"),
    Webhook("Webhook"),
    RingText("Text on the ring"),
    PcKeys("PC keys"),
    MediaKey("Media key"),
    ApiEventOnly("API event only"),
}

val MEDIA_KEYS = listOf("play_pause", "next", "previous", "volume_up", "volume_down")

fun GestureAction.kind(): ActionKind = when (this) {
    GestureAction.None -> ActionKind.None
    is GestureAction.Webhook -> ActionKind.Webhook
    is GestureAction.RingText -> ActionKind.RingText
    is GestureAction.PcKeys -> ActionKind.PcKeys
    is GestureAction.MediaKey -> ActionKind.MediaKey
    GestureAction.ApiEventOnly -> ActionKind.ApiEventOnly
}

fun GestureAction.summary(): String = when (this) {
    GestureAction.None -> "No action"
    is GestureAction.Webhook -> "$method $url"
    is GestureAction.RingText -> "Ring: $text"
    is GestureAction.PcKeys -> "Keys: $keys"
    is GestureAction.MediaKey -> "Media: ${key.replace('_', ' ')}"
    GestureAction.ApiEventOnly -> "API event only"
}

fun freshAction(kind: ActionKind, current: GestureAction): GestureAction = when (kind) {
    ActionKind.None -> GestureAction.None
    ActionKind.Webhook -> current as? GestureAction.Webhook ?: GestureAction.Webhook(url = "https://")
    ActionKind.RingText -> current as? GestureAction.RingText ?: GestureAction.RingText("")
    ActionKind.PcKeys -> current as? GestureAction.PcKeys ?: GestureAction.PcKeys("")
    ActionKind.MediaKey -> current as? GestureAction.MediaKey ?: GestureAction.MediaKey("play_pause")
    ActionKind.ApiEventOnly -> GestureAction.ApiEventOnly
}

fun formatHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

fun parseHeaders(text: String): Map<String, String> =
    text.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) null
        else trimmed.substring(0, colon).trim() to trimmed.substring(colon + 1).trim()
    }.filter { it.first.isNotEmpty() }.toMap()
