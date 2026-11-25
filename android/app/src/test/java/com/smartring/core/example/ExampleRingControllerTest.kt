package com.smartring.core.example

import com.smartring.core.ConnectionState
import com.smartring.core.RingCapability
import com.smartring.core.RingEvent
import com.smartring.core.TouchKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Template test for a new ring driver: drive it only through a FakeTransport (no Bluetooth).
 * Copy this next to your own controller and replace the invented example frames.
 */
class ExampleRingControllerTest {
    private fun frame(cmd: Int, vararg payload: Int) =
        ExampleRingController.Frame.build(cmd, ByteArray(payload.size) { payload[it].toByte() })

    @Test fun connectsReadsBatteryAndSyncsClock() {
        val t = FakeTransport()
        val ring = ExampleRingController(t)
        ring.connect()
        assertEquals(ConnectionState.SCANNING, ring.connection.value.state)

        t.simulateConnected("EXAMPLE-RING")
        assertEquals(ConnectionState.CONNECTED, ring.connection.value.state)
        assertEquals("EXAMPLE-RING", ring.connection.value.deviceName)
        // battery query, then set-time (7 payload bytes = 1 + 6)
        assertEquals(ExampleRingController.Frame.BATTERY_QUERY, t.written[0][1].toInt())
        assertEquals(ExampleRingController.Frame.SET_TIME, t.written[1][1].toInt())

        t.simulateNotify(frame(ExampleRingController.Frame.BATTERY_QUERY, 87))
        assertEquals(87, ring.status.value.batteryPercent)
        assertNotNull(ring.status.value.clockSyncedAtMs)
    }

    @Test fun touchMapsToHoldStartThenTapOrHoldEnd() = runBlocking {
        val t = FakeTransport()
        val ring = ExampleRingController(t)
        val got = mutableListOf<RingEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { ring.events.take(4).toList(got) }

        t.simulateNotify(frame(ExampleRingController.Frame.TOUCH, 0))   // down
        t.simulateNotify(frame(ExampleRingController.Frame.TOUCH, 1))   // short up
        t.simulateNotify(frame(ExampleRingController.Frame.TOUCH, 0))   // down
        t.simulateNotify(frame(ExampleRingController.Frame.TOUCH, 2))   // long up
        withTimeout(2000) { job.join() }

        assertEquals(
            listOf(TouchKind.HOLD_START, TouchKind.TAP, TouchKind.HOLD_START, TouchKind.HOLD_END),
            got.map { (it as RingEvent.Touch).kind },
        )
    }

    @Test fun badChecksumAndSplitFrames() {
        val t = FakeTransport()
        val ring = ExampleRingController(t)
        val good = frame(ExampleRingController.Frame.BATTERY_QUERY, 55)
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() }
        t.simulateNotify(bad)
        assertNull(ring.status.value.batteryPercent)
        t.simulateNotify(good.copyOfRange(0, 2))                          // fragment 1
        t.simulateNotify(good.copyOfRange(2, good.size))                  // fragment 2
        assertEquals(55, ring.status.value.batteryPercent)
    }

    @Test fun severalFramesInOneNotificationAndLeadingGarbage() {
        val t = FakeTransport()
        val ring = ExampleRingController(t)
        val a = frame(ExampleRingController.Frame.BATTERY_QUERY, 40)
        val b = frame(ExampleRingController.Frame.BATTERY_QUERY, 41)
        t.simulateNotify(byteArrayOf(0x01, 0x02) + a + b)                // junk, then two frames at once
        assertEquals(41, ring.status.value.batteryPercent)                 // both parsed, last wins
    }

    @Test fun reconnectsAfterUnexpectedDropButNotAfterDisconnect() {
        val t = FakeTransport()
        val ring = ExampleRingController(t)
        ring.connect()
        t.simulateConnected()
        t.simulateDisconnected()
        assertEquals(2, t.connectCalls)            // retried
        ring.disconnect()
        assertEquals(2, t.connectCalls)            // stays down after the user disconnects
    }

    @Test fun unsupportedFeaturesAreHarmlessAndAdvertisedAsMissing() {
        val ring = ExampleRingController(FakeTransport())
        assertTrue(RingCapability.TOUCH_EVENTS in ring.capabilities)
        assertFalse(RingCapability.MOUSE in ring.capabilities)
        ring.showText("HI"); ring.setBrightness(2); ring.findRing()      // must not throw
        assertNotNull(ring.startRecording("x"))                           // error string, not a crash
        assertNotNull(ring.startFirmwareUpdate(com.smartring.core.FirmwareImage("a", "b", "c"), "00000000"))
        assertTrue(ring.firmwareImages().isEmpty())
        assertNull(ring.sendRaw("01 02"))
        assertNotNull(ring.sendRaw("zz"))
    }
}
