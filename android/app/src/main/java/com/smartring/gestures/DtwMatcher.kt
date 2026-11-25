package com.smartring.gestures

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic Time Warping (DTW) with a Sakoe-Chiba constraint band on multi-dimensional feature sequences.
 */
object DtwMatcher {
    /**
     * Compute distance between two feature vectors.
     */
    fun pointDistance(f1: FeatureVector, f2: FeatureVector): Float {
        val dJerk = f1.jerk - f2.jerk
        val dDyn = f1.dynMag - f2.dynMag
        val dPar = f1.par - f2.par
        val dPerp = f1.perpMag - f2.perpMag
        return sqrt(dJerk * dJerk + dDyn * dDyn + 0.5f * dPar * dPar + 0.5f * dPerp * dPerp)
    }

    /**
     * Compute normalized DTW distance between two feature sequences.
     * [windowSize]: Sakoe-Chiba band width.
     */
    fun distance(s1: List<FeatureVector>, s2: List<FeatureVector>, windowSize: Int = 6): Float {
        val n = s1.size
        val m = s2.size
        if (n == 0 || m == 0) return Float.MAX_VALUE

        val w = max(windowSize, kotlin.math.abs(n - m))

        // Cost matrix of size (n+1) x (m+1)
        val dp = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val jStart = max(1, i - w)
            val jEnd = min(m, i + w)
            val f1 = s1[i - 1]
            for (j in jStart..jEnd) {
                val dist = pointDistance(f1, s2[j - 1])
                val prevMin = min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
                dp[i][j] = dist + prevMin
            }
        }

        val totalCost = dp[n][m]
        return if (totalCost.isInfinite()) Float.MAX_VALUE else totalCost / (n + m).toFloat()
    }
}
