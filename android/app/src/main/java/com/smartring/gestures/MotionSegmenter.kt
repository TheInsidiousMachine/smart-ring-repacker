package com.smartring.gestures

import kotlin.math.max

/**
 * A segmented candidate motion burst from the ring's ~100 Hz accelerometer stream.
 */
data class CandidateBurst(
    val rawSamples: List<IntArray>,
    val features: List<FeatureVector>,
    val length: Int,
    val peakJerk: Float,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Online motion segmenter.
 * Uses activity (jerk / dynamic acceleration) with hysteresis and a quiet window
 * to segment continuous accelerometer samples into discrete gesture bursts.
 */
class MotionSegmenter(
    val startJerk: Float = 0.5f,
    val quietJerk: Float = 0.20f,
    val quietWindow: Int = 30,
    val minLen: Int = 12,
    val maxLen: Int = 200,
    val minPeakJerk: Float = 0.9f,
    val preRollSize: Int = 6,
) {
    private val extractor = FeatureExtractor()

    private val preRollRaw = ArrayDeque<IntArray>()
    private val preRollFeats = ArrayDeque<FeatureVector>()
    private val preRollTimes = ArrayDeque<Long>()

    private val currentRaw = mutableListOf<IntArray>()
    private val currentFeats = mutableListOf<FeatureVector>()
    private val currentTimes = mutableListOf<Long>()

    private var inBurst = false
    private var quietCount = 0
    private var peakJerk = 0f

    fun reset() {
        extractor.reset()
        preRollRaw.clear()
        preRollFeats.clear()
        preRollTimes.clear()
        currentRaw.clear()
        currentFeats.clear()
        currentTimes.clear()
        inBurst = false
        quietCount = 0
        peakJerk = 0f
    }

    /**
     * Feed one sample [raw]: [x, y, z]. Returns a [CandidateBurst] if a motion burst just completed.
     */
    fun feedSample(raw: IntArray, atMs: Long): CandidateBurst? {
        val feat = extractor.processSample(raw)

        if (!inBurst) {
            preRollRaw.addLast(raw)
            preRollFeats.addLast(feat)
            preRollTimes.addLast(atMs)
            if (preRollRaw.size > preRollSize) {
                preRollRaw.removeFirst()
                preRollFeats.removeFirst()
                preRollTimes.removeFirst()
            }

            if (feat.jerk > startJerk) {
                // Motion burst begins: seed with pre-roll buffer
                inBurst = true
                currentRaw.clear()
                currentFeats.clear()
                currentTimes.clear()
                currentRaw.addAll(preRollRaw)
                currentFeats.addAll(preRollFeats)
                currentTimes.addAll(preRollTimes)
                quietCount = 0
                peakJerk = currentFeats.maxOfOrNull { it.jerk } ?: feat.jerk
            }
            return null
        }

        // Currently in a burst
        currentRaw.add(raw)
        currentFeats.add(feat)
        currentTimes.add(atMs)
        if (feat.jerk > peakJerk) {
            peakJerk = feat.jerk
        }

        val burstLen = currentRaw.size
        var burstDone = false
        var trimmedLen = burstLen

        if (feat.jerk < quietJerk) {
            quietCount++
            if (quietCount >= quietWindow) {
                burstDone = true
                // Trim trailing quiet samples from the burst
                trimmedLen = max(minLen, burstLen - quietCount)
            }
        } else {
            quietCount = 0
        }

        if (burstLen >= maxLen) {
            burstDone = true
            trimmedLen = burstLen
        }

        if (burstDone) {
            val finalRaw = currentRaw.take(trimmedLen)
            val finalFeats = currentFeats.take(trimmedLen)
            val finalPeak = finalFeats.maxOfOrNull { it.jerk } ?: peakJerk
            val startMs = currentTimes.firstOrNull() ?: atMs
            val endMs = if (trimmedLen > 0 && trimmedLen <= currentTimes.size) currentTimes[trimmedLen - 1] else atMs

            val result = if (trimmedLen >= minLen && finalPeak >= minPeakJerk) {
                CandidateBurst(finalRaw, finalFeats, trimmedLen, finalPeak, startMs, endMs)
            } else null

            // Reset burst state
            inBurst = false
            quietCount = 0
            peakJerk = 0f
            currentRaw.clear()
            currentFeats.clear()
            currentTimes.clear()
            preRollRaw.clear()
            preRollFeats.clear()
            preRollTimes.clear()

            return result
        }

        return null
    }

    /**
     * Batch process a sequence of samples (e.g. from a test file or pre-recorded batch).
     */
    fun segment(samples: List<IntArray>, startTimestampMs: Long = 0L, samplePeriodMs: Long = 10L): List<CandidateBurst> {
        reset()
        val results = mutableListOf<CandidateBurst>()
        var t = startTimestampMs
        for (s in samples) {
            val burst = feedSample(s, t)
            if (burst != null) {
                results.add(burst)
            }
            t += samplePeriodMs
        }
        return results
    }
}
