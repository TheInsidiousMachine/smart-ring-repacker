package com.smartring.gestures

import kotlin.math.max

data class GestureMatch(
    val gestureId: String,
    val name: String,
    val distance: Float,
    val confidence: Double,
)

data class TrainedExample(
    val rawSamples: List<IntArray>,
    val length: Int,
    val peakJerk: Float,
    val resampledFeatures: List<FeatureVector>,
)

class TrainedGestureModel(
    val id: String,
    val name: String,
    val strictness: Int,
    val examples: List<TrainedExample>,
) {
    val isTrained: Boolean get() = examples.size >= 3

    val meanLength: Float = if (examples.isNotEmpty()) {
        examples.map { it.length }.average().toFloat()
    } else 0f

    val minPeakJerk: Float = if (examples.isNotEmpty()) {
        examples.minOf { it.peakJerk }
    } else 0f

    val maxPairDistance: Float
    val meanPairDistance: Float
    val rejectionThreshold: Float

    init {
        if (examples.size >= 2) {
            val distances = mutableListOf<Float>()
            for (i in examples.indices) {
                for (j in i + 1 until examples.size) {
                    val d = DtwMatcher.distance(examples[i].resampledFeatures, examples[j].resampledFeatures)
                    distances += d
                }
            }
            meanPairDistance = distances.average().toFloat()
            maxPairDistance = distances.maxOrNull() ?: 0.35f
        } else {
            meanPairDistance = 0.35f
            maxPairDistance = 0.40f
        }

        // Higher strictness (0..100) -> smaller threshold (fewer false fires)
        // strictness=60 -> factor 1.0
        val strictnessFactor = 1.0f + (60 - strictness) / 100.0f * 0.5f
        val baseThreshold = max(0.30f, maxPairDistance * 1.08f)
        rejectionThreshold = baseThreshold * strictnessFactor
    }

    /**
     * Match a candidate burst against this gesture's templates.
     * Returns a [GestureMatch] if accepted, or null if rejected.
     */
    fun match(burst: CandidateBurst): GestureMatch? {
        if (!isTrained) return null

        // 1. Duration check
        val lenRatio = burst.length.toFloat() / max(1f, meanLength)
        if (lenRatio < 0.60f || lenRatio > 1.70f) {
            return null
        }

        // 2. Minimum peak jerk check (candidate must reach at least 70% of training peak)
        if (burst.peakJerk < minPeakJerk * 0.70f) {
            return null
        }

        // 3. Resample candidate burst to fixed length 32
        val candidateResampled = FeatureExtractor.resample(burst.features, 32)

        // 4. DTW distance to nearest template
        var minD = Float.MAX_VALUE
        for (ex in examples) {
            val d = DtwMatcher.distance(candidateResampled, ex.resampledFeatures)
            if (d < minD) {
                minD = d
            }
        }

        if (minD <= rejectionThreshold) {
            // Confidence scaled between 0.50 (at threshold) and 1.0 (at distance 0)
            val conf = (1.0 - (minD / rejectionThreshold) * 0.5).coerceIn(0.0, 1.0)
            return GestureMatch(id, name, minD, conf)
        }

        return null
    }
}
