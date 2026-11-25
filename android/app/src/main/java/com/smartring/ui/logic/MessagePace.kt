package com.smartring.ui.logic

/**
 * Named speeds for the bump message. The fine slider still covers 60..1500 ms.
 * Fast is the firmware default (160 ms per step).
 */
object MessagePace {
    const val FAST = 160
    const val NORMAL = 400
    const val SLOW = 800

    val presets = listOf("Fast" to FAST, "Normal" to NORMAL, "Slow" to SLOW)

    fun label(ms: Int): String = presets.firstOrNull { it.second == ms }?.first ?: "$ms ms"
}
