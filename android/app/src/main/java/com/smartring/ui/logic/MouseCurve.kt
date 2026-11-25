package com.smartring.ui.logic

import kotlin.math.abs

/**
 * Visual model of the tilt joystick. The firmware owns the real curve; this is the
 * shape the Mouse screen draws so speed, curve, and deadzone are obvious.
 *
 * Tilt is −1..1 (full scale ≈ 45°). Deadzone degrees are mapped across that same 45°.
 * Curve 0 is linear. Curve 100 is cubic, so a small lean barely moves and a deep lean runs.
 */
object MouseCurve {
    fun response(tilt: Float, curve: Int, deadzoneFraction: Float): Float {
        val dead = deadzoneFraction.coerceIn(0f, 0.95f)
        val magnitude = abs(tilt).coerceIn(0f, 1f)
        if (magnitude <= dead) return 0f
        val sign = if (tilt < 0f) -1f else 1f
        val x = ((magnitude - dead) / (1f - dead)).coerceIn(0f, 1f)
        val k = curve.coerceIn(0, 100) / 100f
        val shaped = (1f - k) * x + k * x * x * x
        return sign * shaped
    }

    fun samples(curve: Int, deadzoneDeg: Int, points: Int = 49): List<Pair<Float, Float>> {
        val dead = deadzoneDeg.coerceIn(0, 15) / 45f
        val n = points.coerceAtLeast(2)
        return (0 until n).map { i ->
            val tilt = -1f + 2f * i / (n - 1)
            tilt to response(tilt, curve, dead)
        }
    }

    /**
     * Pointer style. [handSpeed] is 0..1. A still hand is speed 0, so the gain is 0
     * and the cursor does not drift. [sensitivity] is the speed setting, 1..100.
     */
    fun pointerGain(handSpeed: Float, curve: Int, sensitivity: Int): Float {
        val x = handSpeed.coerceIn(0f, 1f)
        val k = curve.coerceIn(0, 100) / 100f
        val shaped = (1f - k) * x + k * x * x * x
        return shaped * (sensitivity.coerceIn(1, 100) / 100f)
    }

    /** Hand speed 0..1 against cursor gain, for the pointer graph. */
    fun pointerSamples(curve: Int, sensitivity: Int, points: Int = 49): List<Pair<Float, Float>> {
        val n = points.coerceAtLeast(2)
        return (0 until n).map { i ->
            val speed = i / (n - 1).toFloat()
            speed to pointerGain(speed, curve, sensitivity)
        }
    }
}
