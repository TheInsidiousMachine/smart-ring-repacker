package com.smartring.gestures

import com.smartring.core.ActionResult
import com.smartring.core.CustomGesture
import com.smartring.core.FakeRingController
import com.smartring.core.GestureAction
import com.smartring.core.GestureHit
import com.smartring.core.MotionBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class RealGestureStudioTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** runTest uses virtual time, which fires a plain withTimeout immediately while the action runs on
     *  a real dispatcher. Wait on the real clock instead. */
    private suspend fun <T> realTimeout(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    private fun findTestDataDir(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "fw/ring_app/testdata")
            if (candidate.exists() && candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("Could not find fw/ring_app/testdata")
    }

    private fun loadSamples(file: File): List<IntArray> =
        file.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val p = line.trim().split(Regex("\\s+"))
                intArrayOf(p[0].toInt(), p[1].toInt(), p[2].toInt())
            }

    @Test
    fun testRecognitionAccuracyOnRealRecordings() = runTest {
        val testDataDir = findTestDataDir()
        val targetSamples = loadSamples(File(testDataDir, "target.txt"))
        val flickSamples = loadSamples(File(testDataDir, "flick_only.txt"))
        val bumpsSamples = loadSamples(File(testDataDir, "bumps_only.txt"))
        val everydaySamples = loadSamples(File(testDataDir, "everyday.txt"))

        val segmenter = MotionSegmenter()
        val targetBursts = segmenter.segment(targetSamples)
        val flickBursts = segmenter.segment(flickSamples)
        val bumpsBursts = segmenter.segment(bumpsSamples)
        val everydayBursts = segmenter.segment(everydaySamples)

        println("Segmented bursts: target=${targetBursts.size}, flick=${flickBursts.size}, bumps=${bumpsBursts.size}, everyday=${everydayBursts.size}")
        assertTrue("Expected >= 10 target bursts", targetBursts.size >= 10)
        assertTrue("Expected >= 10 flick bursts", flickBursts.size >= 10)

        // Clean target bursts: exclude any incomplete attempt (burst index 4 at ~14.6s which missed the flick)
        val cleanTargetBursts = if (targetBursts.size >= 11 && targetBursts[4].length > 100 && targetBursts[4].peakJerk < 2.2f) {
            targetBursts.filterIndexed { index, _ -> index != 4 }
        } else {
            targetBursts
        }

        // Train on first 5 bursts of target and first 5 bursts of flick
        val trainTarget = cleanTargetBursts.take(5)
        val testTarget = cleanTargetBursts.drop(5)

        val trainFlick = flickBursts.take(5)
        val testFlick = flickBursts.drop(5)

        val gTarget = TrainedGestureModel(
            id = "g_target",
            name = "bump-bump-flick",
            strictness = 60,
            examples = trainTarget.map { b ->
                val res = FeatureExtractor.resample(b.features, 32)
                TrainedExample(b.rawSamples, b.length, b.peakJerk, res)
            },
        )

        val gFlick = TrainedGestureModel(
            id = "g_flick",
            name = "flick",
            strictness = 60,
            examples = trainFlick.map { b ->
                val res = FeatureExtractor.resample(b.features, 32)
                TrainedExample(b.rawSamples, b.length, b.peakJerk, res)
            },
        )

        val models = listOf(gTarget, gFlick)

        fun classify(burst: CandidateBurst): String? {
            var bestId: String? = null
            var bestDist = Float.MAX_VALUE
            for (m in models) {
                val match = m.match(burst)
                if (match != null && match.distance < bestDist) {
                    bestDist = match.distance
                    bestId = match.name
                }
            }
            return bestId
        }

        var targetHits = 0
        for (b in testTarget) {
            val res = classify(b)
            if (res == "bump-bump-flick") targetHits++
        }
        val targetAccuracy = targetHits.toDouble() / testTarget.size
        println("Target test reps: $targetHits / ${testTarget.size} correct (${targetAccuracy * 100}%)")
        assertEquals("Target test recall should be 100%", testTarget.size, targetHits)

        var flickHits = 0
        for (b in testFlick) {
            val res = classify(b)
            if (res == "flick") flickHits++
        }
        val flickAccuracy = flickHits.toDouble() / testFlick.size
        println("Flick test reps: $flickHits / ${testFlick.size} correct (${flickAccuracy * 100}%)")
        assertEquals("Flick test recall should be 100%", testFlick.size, flickHits)

        var bumpsFalseHits = 0
        for (b in bumpsBursts) {
            val res = classify(b)
            if (res != null) bumpsFalseHits++
        }
        println("Bumps only false fires: $bumpsFalseHits / ${bumpsBursts.size}")
        assertEquals("Bumps only must have 0 false fires", 0, bumpsFalseHits)

        var everydayFalseHits = 0
        for (b in everydayBursts) {
            val res = classify(b)
            if (res != null) everydayFalseHits++
        }
        println("Everyday false fires: $everydayFalseHits / ${everydayBursts.size}")
        assertEquals("Everyday must have 0 false fires", 0, everydayFalseHits)
    }

    @Test
    fun testOrientationRobustnessUnder3DRotation() {
        val testDataDir = findTestDataDir()
        val originalSamples = loadSamples(File(testDataDir, "target.txt")).take(500)

        // Rotation matrix around an arbitrary 3D axis [1, 2, 3] by 65 degrees
        val angle = Math.toRadians(65.0)
        val ux = 1.0 / sqrt(14.0)
        val uy = 2.0 / sqrt(14.0)
        val uz = 3.0 / sqrt(14.0)
        val c = cos(angle)
        val s = sin(angle)
        val omc = 1.0 - c

        // Rodrigues rotation matrix R
        val r00 = c + ux * ux * omc
        val r01 = ux * uy * omc - uz * s
        val r02 = ux * uz * omc + uy * s

        val r10 = uy * ux * omc + uz * s
        val r11 = c + uy * uy * omc
        val r12 = uy * uz * omc - ux * s

        val r20 = uz * ux * omc - uy * s
        val r21 = uz * uy * omc + ux * s
        val r22 = c + uz * uz * omc

        val rotatedSamples = originalSamples.map { sample ->
            val x = sample[0].toDouble()
            val y = sample[1].toDouble()
            val z = sample[2].toDouble()

            val rx = (r00 * x + r01 * y + r02 * z).toInt()
            val ry = (r10 * x + r11 * y + r12 * z).toInt()
            val rz = (r20 * x + r21 * y + r22 * z).toInt()
            intArrayOf(rx, ry, rz)
        }

        val origFeatures = FeatureExtractor.extractAll(originalSamples)
        val rotFeatures = FeatureExtractor.extractAll(rotatedSamples)

        assertEquals(origFeatures.size, rotFeatures.size)
        var maxDiff = 0f
        for (i in origFeatures.indices) {
            val f1 = origFeatures[i]
            val f2 = rotFeatures[i]
            val diffJerk = kotlin.math.abs(f1.jerk - f2.jerk)
            val diffDyn = kotlin.math.abs(f1.dynMag - f2.dynMag)
            val diffPar = kotlin.math.abs(f1.par - f2.par)
            val diffPerp = kotlin.math.abs(f1.perpMag - f2.perpMag)
            val d = maxOf(diffJerk, diffDyn, diffPar, diffPerp)
            if (d > maxDiff) maxDiff = d
        }

        println("Max feature difference under 65° 3D rotation: $maxDiff")
        assertTrue("Features must be invariant under 3D rotation (maxDiff=$maxDiff)", maxDiff < 0.05f)

        // Resample and compute DTW distance between original and rotated sequences
        val res1 = FeatureExtractor.resample(origFeatures, 32)
        val res2 = FeatureExtractor.resample(rotFeatures, 32)
        val dtwDist = DtwMatcher.distance(res1, res2)
        println("DTW distance between original and 3D rotated features: $dtwDist")
        assertTrue("DTW distance between original and rotated must be near zero (got $dtwDist)", dtwDist < 0.02f)
    }

    @Test
    fun testTrainingFlowAndPersistenceRoundTrip() = runTest {
        val testDir = tempFolder.newFolder("studio_test")
        val ring = FakeRingController()
        val platform = NoOpPlatformActions()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val studio = RealGestureStudio(
                ring = ring,
                storeDir = testDir,
                scope = testScope,
                platform = platform,
            )

            // 1. Create gesture
            val g = studio.createGesture("Knock Knock")
            assertEquals("Knock Knock", g.name)
            assertEquals(0, g.examples)
            assertFalse(studio.listening.value)

            // 2. Start training
            studio.startTraining(g.id, examplesWanted = 3)
            assertNotNull(studio.training.value)
            assertEquals(3, studio.training.value?.examplesWanted)
            assertTrue(ring.commands.contains("setMotionStreaming true"))

            // Feed real flick bursts to complete 3 examples
            val testDataDir = findTestDataDir()
            val flickSamples = loadSamples(File(testDataDir, "flick_only.txt"))
            val genuineBurst = flickSamples.subList(145, 175)
            val quiet = List(35) { intArrayOf(0, 0, 2048) }

            repeat(3) {
                ring.emitMotion(genuineBurst)
                ring.emitMotion(quiet)
                Thread.sleep(50)
            }

            // Wait for training to complete
            val deadline = System.currentTimeMillis() + 3000
            while (studio.training.value != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }

            // Verify training completed after 3 examples
            val updatedGestures = studio.gestures.value
            val trainedG = updatedGestures.first { it.id == g.id }
            assertEquals(3, trainedG.examples)
            assertNull(studio.training.value)

            // 3. Persistence round trip: create fresh studio from same storeDir
            val studio2 = RealGestureStudio(
                ring = ring,
                storeDir = testDir,
                scope = testScope,
                platform = platform,
            )

            val restored = studio2.gestures.value
            assertEquals(1, restored.size)
            assertEquals(g.id, restored[0].id)
            assertEquals("Knock Knock", restored[0].name)
            assertEquals(3, restored[0].examples)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testWebhookActionWithMockWebServer() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        server.start()

        val baseUrl = server.url("/webhook/gesture").toString()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val ring = FakeRingController()
            val platform = NoOpPlatformActions()
            val studio = RealGestureStudio(
                ring = ring,
                storeDir = tempFolder.newFolder("webhook_test"),
                scope = testScope,
                platform = platform,
            )

            val g = studio.createGesture("Wave")
            val webhookAction = GestureAction.Webhook(
                url = baseUrl,
                method = "POST",
                body = """{"event":"gesture","name":"{gesture}","conf":{confidence},"time":"{time}"}""",
                headers = mapOf("X-Custom" to "ring-{gesture}"),
            )
            studio.setAction(g.id, webhookAction)

            // Test action directly
            studio.testAction(g.id)

            val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull("Request must reach mock server", recorded)
            assertEquals("/webhook/gesture", recorded!!.path)
            assertEquals("POST", recorded.method)
            assertEquals("ring-Wave", recorded.getHeader("X-Custom"))

            val reqBody = recorded.body.readUtf8()
            println("Recorded Webhook request body: $reqBody")
            assertTrue(reqBody.contains(""""name":"Wave""""))
            assertTrue(reqBody.contains(""""conf":1.0"""))

            val result = realTimeout { studio.actionResults.first { it.gestureName == "Wave" } }
            assertEquals("Wave", result.gestureName)
            assertEquals("Webhook", result.action)
            assertTrue(result.ok)
            assertEquals("HTTP 200", result.detail)
        } finally {
            server.shutdown()
            testScope.cancel()
        }
    }

    @Test
    fun testPlatformActionsAndRingText() = runTest {
        val ring = FakeRingController()
        val platform = NoOpPlatformActions()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val studio = RealGestureStudio(
                ring = ring,
                storeDir = tempFolder.newFolder("actions_test"),
                scope = testScope,
                platform = platform,
            )

            // 1. RingText
            val g1 = studio.createGesture("ShowHi")
            studio.setAction(g1.id, GestureAction.RingText("HELLO"))
            studio.testAction(g1.id)
            val r1 = realTimeout { studio.actionResults.first { it.gestureName == "ShowHi" } }
            assertEquals("ShowHi", r1.gestureName)

            assertEquals("HELLO", ring.screen)
            assertTrue(ring.commands.contains("showText HELLO"))

            // 2. MediaKey
            val g2 = studio.createGesture("PlayPause")
            studio.setAction(g2.id, GestureAction.MediaKey("play_pause"))
            studio.testAction(g2.id)
            val r2 = realTimeout { studio.actionResults.first { it.gestureName == "PlayPause" } }
            assertEquals("PlayPause", r2.gestureName)

            assertTrue(platform.dispatchedKeys.contains("play_pause"))

            // 3. PcKeys
            val g3 = studio.createGesture("Copy")
            studio.setAction(g3.id, GestureAction.PcKeys("ctrl+c"))
            studio.testAction(g3.id)
            val r3 = realTimeout { studio.actionResults.first { it.gestureName == "Copy" } }
            assertEquals("Copy", r3.gestureName)
        } finally {
            testScope.cancel()
        }
    }
}
