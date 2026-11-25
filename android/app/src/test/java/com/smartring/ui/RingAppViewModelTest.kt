package com.smartring.ui

import com.smartring.core.ApiSettings
import com.smartring.core.ConnectionState
import com.smartring.core.FakeGestureStudio
import com.smartring.core.FakeRingController
import com.smartring.core.GestureAction
import com.smartring.core.GestureMessage
import com.smartring.core.MouseSettings
import com.smartring.core.MouseStyle
import com.smartring.core.RingEvent
import com.smartring.core.RingMode
import com.smartring.ui.logic.DevCommands
import com.smartring.ui.prefs.MemoryUiPreferences
import com.smartring.ui.vm.RingAppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class RingAppViewModelTest {
    @get:Rule
    val mainRule = object : TestWatcher() {
        private val dispatcher = UnconfinedTestDispatcher()
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    private lateinit var ring: FakeRingController
    private lateinit var studio: FakeGestureStudio
    private lateinit var prefs: MemoryUiPreferences
    private lateinit var vm: RingAppViewModel
    private var issued = 0

    @Before fun setUp() {
        ring = FakeRingController()
        studio = FakeGestureStudio()
        prefs = MemoryUiPreferences()
        issued = 0
        vm = RingAppViewModel(ring, studio, prefs) { "tok%02d".format(issued++) }
    }

    @After fun tearDown() {
        vm.clear()
    }

    private fun commands() = synchronized(ring.commands) { ring.commands.toList() }

    @Test fun connectDrivesTheRingControls() {
        vm.connect()
        assertEquals(ConnectionState.CONNECTED, vm.connection.value.state)
        vm.setMode(RingMode.MOUSE)
        vm.setHeart(true)
        assertEquals(RingMode.HEART, vm.status.value.mode)
        vm.setHeart(false)
        assertEquals(RingMode.CLOCK, vm.status.value.mode)
        vm.setBrightness(3)
        vm.setScreenTimeout(15)
        vm.setGestureDetector(false)
        vm.showText("Hi")
        vm.syncClock()
        vm.findRing()
        vm.setMouseSettings(
            MouseSettings(
                style = MouseStyle.JOYSTICK,
                speed = 80,
                curve = 20,
                deadzoneDeg = 6,
                invertX = true,
                swapAxes = true,
                rightClickHoldMs = 900,
            ),
        )
        vm.setGestureMessage(GestureMessage("hi there", scroll = true, msPerStep = 40, plays = 9))
        vm.playGestureMessage()
        val sent = commands()
        assertTrue(sent.any { it == "setMode MOUSE" })
        assertTrue(sent.any { it == "setMode HEART" })
        assertTrue(sent.any { it == "setBrightness 3" })
        assertTrue(sent.any { it == "setScreenTimeout 15" })
        assertTrue(sent.any { it == "showText Hi" })
        assertEquals(60, vm.gestureMessage.value.msPerStep)
        assertEquals(5, vm.gestureMessage.value.plays)
        assertEquals(80, vm.mouseSettings.value.speed)
        assertEquals(MouseStyle.JOYSTICK, vm.mouseSettings.value.style)
        assertTrue(vm.mouseSettings.value.invertX)
        assertTrue(vm.mouseSettings.value.swapAxes)
        assertEquals(3, vm.status.value.screenBrightness)
        assertFalse(vm.status.value.gestureDetectorOn!!)
    }

    @Test fun devModeAndSavedMessagesPersistOnThePreferencePort() {
        vm.setDevMode(true)
        assertTrue(prefs.devMode)
        assertTrue(vm.devMode.value)
        vm.saveQuickMessage("Hello")
        vm.saveQuickMessage("Hello")
        vm.saveQuickMessage("  ")
        assertEquals(listOf("Hello"), vm.savedMessages.value)
        vm.saveQuickMessage("Later")
        assertEquals(listOf("Later", "Hello"), vm.savedMessages.value)
        vm.removeQuickMessage("Hello")
        assertEquals(listOf("Later"), vm.savedMessages.value)
    }

    @Test fun heartBeatUpdatesTheLivePulse() {
        ring.emitEvent(RingEvent.HeartBeat(64, 5))
        assertEquals(64, vm.liveBpm.value)
        assertEquals(1, vm.heartPulse.value)
        assertEquals("Heartbeat 64", com.smartring.ui.logic.eventLabel(vm.recentEvents.value.first()))
    }

    @Test fun consoleFiltersClearsAndRejectsBadHex() {
        ring.log("alpha")
        assertTrue(vm.consoleLines.value.any { it.text == "alpha" })
        vm.setLogFilter("nope")
        assertTrue(vm.consoleLines.value.isEmpty())
        vm.setLogFilter("ALP")
        assertEquals(listOf("alpha"), vm.consoleLines.value.map { it.text })
        vm.setLogFilter("")
        vm.clearConsole()
        assertTrue(vm.consoleLines.value.isEmpty())
        ring.log("beta")
        assertEquals(listOf("beta"), vm.consoleLines.value.map { it.text })
        assertNotNull(vm.sendRaw("zz"))
        assertNull(vm.sendRaw("02 04 10"))
    }

    @Test fun firmwareRequiresAConnectionAndTheHashPrefix() = runTest {
        val image = ring.firmwareImages().first()
        assertEquals("Not connected", vm.startFirmwareUpdate(image, image.sha256.take(8)))
        vm.connect()
        assertNotNull(vm.startFirmwareUpdate(image, "short"))
        assertNotNull(vm.startFirmwareUpdate(image, image.sha256.uppercase()))
        assertNull(vm.startFirmwareUpdate(image, image.sha256.take(8).uppercase()))
        val live = FakeRingController(scope = this)
        val updating = RingAppViewModel(live, studio, prefs)
        updating.connect()
        assertNull(updating.startFirmwareUpdate(live.firmwareImages().first(), "8fb5ee37"))
        advanceUntilIdle()
        assertEquals(true, live.ota.value?.done)
        updating.clear()
        vm.disconnect()
        assertEquals("Not connected", vm.powerOff())
        vm.connect()
        assertNull(vm.powerOff())
        assertTrue(commands().any { it == "powerOff" })
    }

    @Test fun messageNoticeFollowsConnectionAndRecenterIsALocalAdapter() = runTest {
        val notes = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notes += it } }
        vm.setGestureMessage(GestureMessage())
        assertEquals("Saved. Sends when the ring connects", notes.last())
        vm.connect()
        vm.setGestureMessage(GestureMessage("HELLO"))
        assertEquals("Sent to ring", notes.last())
        vm.recenterMouse()
        assertEquals("Neutral recaptured", notes.last())
        assertTrue(commands().any { it == "raw 7A 0B 00" })
        job.cancel()
    }

    @Test fun recordingAndPresets() {
        vm.setRecordLabel("not-a-label")
        assertEquals("target", vm.recordLabel.value)
        vm.setRecordLabel("flick_only")
        assertNull(ring.recording.value)
        vm.toggleRecording()
        assertEquals("flick_only", ring.recording.value?.label)
        vm.toggleRecording()
        assertNull(ring.recording.value)
        vm.runPreset(DevCommands.reset)
        assertTrue(commands().any { it.startsWith("raw 02 67 10") })
    }

    @Test fun gesturesTrainAndTheApiMintsAToken() {
        val created = vm.createGesture("  Nod  ")
        assertNotNull(created)
        val id = created!!.id
        vm.renameGesture(id, "Big nod")
        vm.setEnabled(id, false)
        vm.setStrictness(id, 140)
        vm.setAction(id, GestureAction.MediaKey("next"))
        vm.startTraining(id)
        assertEquals(id, vm.training.value?.gestureId)
        vm.deleteGesture(id)
        assertNull(vm.training.value)
        assertTrue(vm.gestures.value.none { it.id == id })
        assertNull(vm.createGesture("   "))

        studio.testAction("g1")
        assertTrue(vm.activity.value.any { it.title == "Swipe right" && it.detail.contains("93") })
        assertTrue(vm.activity.value.any { it.ok == true })

        assertEquals("Port must be 1–65535", vm.updateApi(ApiSettings(enabled = true, port = 0)))
        assertNull(vm.updateApi(ApiSettings(enabled = true, port = 9000, lan = false, token = "")))
        assertEquals("tok00", vm.apiSettings.value.token)
        assertEquals(listOf("http://192.0.2.10:9000"), vm.apiStatus.value.urls)
        vm.regenerateToken()
        assertEquals("tok01", vm.apiSettings.value.token)
        vm.setListening(true)
        assertTrue(vm.listening.value)
    }
}
