package com.smartring.gestures

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Orientation-robust scalar features extracted from 3-axis accelerometer stream.
 * Invariant under any 3D rotation of the sensor frame (e.g. ring rotated around the finger).
 */
@Serializable
data class FeatureVector(
    val jerk: Float,
    val dynMag: Float,
    val par: Float,
    val perpMag: Float,
)

class FeatureExtractor(val alpha: Float = 0.04f) {
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var prevAx = 0f
    private var prevAy = 0f
    private var prevAz = 0f
    private var initialized = false

    fun reset() {
        initialized = false
        gx = 0f; gy = 0f; gz = 0f
        prevAx = 0f; prevAy = 0f; prevAz = 0f
    }

    /**
     * Process one accelerometer sample.
     * [rawSample]: [x, y, z] in raw>>2 units (~2048 per g).
     */
    fun processSample(rawSample: IntArray): FeatureVector {
        val ax = rawSample[0] / 2048f
        val ay = rawSample[1] / 2048f
        val az = rawSample[2] / 2048f
        return processSampleG(ax, ay, az)
    }

    fun processSampleG(ax: Float, ay: Float, az: Float): FeatureVector {
        if (!initialized) {
            gx = ax
            gy = ay
            gz = az
            prevAx = ax
            prevAy = ay
            prevAz = az
            initialized = true
            return FeatureVector(0f, 0f, 0f, 0f)
        }

        // Update low-pass gravity estimate
        gx += alpha * (ax - gx)
        gy += alpha * (ay - gy)
        gz += alpha * (az - gz)

        // Dynamic acceleration: a_dyn = a - g
        val dynX = ax - gx
        val dynY = ay - gy
        val dynZ = az - gz
        val dynMag = sqrt(dynX * dynX + dynY * dynY + dynZ * dynZ)

        // Jerk: delta a = a - prevA
        val dAx = ax - prevAx
        val dAy = ay - prevAy
        val dAz = az - prevAz
        val jerk = sqrt(dAx * dAx + dAy * dAy + dAz * dAz)

        prevAx = ax
        prevAy = ay
        prevAz = az

        // Components relative to unit gravity vector
        val gLen = sqrt(gx * gx + gy * gy + gz * gz)
        val ghx = if (gLen > 1e-6f) gx / gLen else 0f
        val ghy = if (gLen > 1e-6f) gy / gLen else 0f
        val ghz = if (gLen > 1e-6f) gz / gLen else 1f

        // Parallel to gravity: dyn . g_hat
        val par = dynX * ghx + dynY * ghy + dynZ * ghz

        // Perpendicular vector to gravity: dyn - par * g_hat
        val perpX = dynX - par * ghx
        val perpY = dynY - par * ghy
        val perpZ = dynZ - par * ghz
        val perpMag = sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ)

        return FeatureVector(jerk, dynMag, par, perpMag)
    }

    companion object {
        /**
         * Resample a variable-length list of feature vectors to a fixed target length via linear interpolation.
         */
        fun resample(features: List<FeatureVector>, targetLength: Int = 32): List<FeatureVector> {
            require(targetLength > 1) { "targetLength must be > 1" }
            if (features.isEmpty()) return emptyList()
            if (features.size == targetLength) return features
            if (features.size == 1) return List(targetLength) { features[0] }

            val origN = features.size
            return List(targetLength) { i ->
                val t = i.toFloat() / (targetLength - 1).toFloat()
                val origIdx = t * (origN - 1).toFloat()
                val i0 = origIdx.toInt().coerceIn(0, origN - 2)
                val i1 = i0 + 1
                val frac = origIdx - i0

                val f0 = features[i0]
                val f1 = features[i1]
                FeatureVector(
                    jerk = f0.jerk + frac * (f1.jerk - f0.jerk),
                    dynMag = f0.dynMag + frac * (f1.dynMag - f0.dynMag),
                    par = f0.par + frac * (f1.par - f0.par),
                    perpMag = f0.perpMag + frac * (f1.perpMag - f0.perpMag),
                )
            }
        }

        /**
         * Extract features for an entire sequence of raw accelerometer samples.
         */
        fun extractAll(samples: List<IntArray>, alpha: Float = 0.04f): List<FeatureVector> {
            val extractor = FeatureExtractor(alpha)
            return samples.map { extractor.processSample(it) }
        }
    }
}
