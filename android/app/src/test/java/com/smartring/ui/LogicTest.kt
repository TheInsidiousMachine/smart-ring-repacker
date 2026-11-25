package com.smartring.ui

import com.smartring.core.Connection
import com.smartring.core.ConnectionState
import com.smartring.core.FakeRingController
import com.smartring.core.FirmwareImage
import com.smartring.core.GestureAction
import com.smartring.core.GestureMessage
import com.smartring.core.RingStatus
import com.smartring.testbed.RingProtocol
import com.smartring.ui.logic.ActionKind
import com.smartring.ui.logic.DevCommands
import com.smartring.ui.logic.FirmwareCatalog
import com.smartring.ui.logic.MessagePreview
import com.smartring.ui.logic.MouseCurve
import com.smartring.ui.logic.SegmentFont
import com.smartring.ui.logic.formatClock
import com.smartring.ui.logic.formatHeaders
import com.smartring.ui.logic.freshAction
import com.smartring.ui.logic.kind
import com.smartring.ui.logic.parseHeaders
import com.smartring.ui.logic.statusDump
import com.smartring.ui.logic.summary
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun previewMatchesProtocol() {
        val samples = listOf(
            "four off buddy" to false,
            "helloworld !" to false,
            "HI" to true,
            "x".repeat(100) to true,
            "" to false,
            "  " to true,
        )
        for ((text, scroll) in samples) {
            assertEquals(RingProtocol.messageFrames(text, scroll), MessagePreview.protocolFrames(text, scroll))
        }
    }

    @Test fun blankGestureUsesBuiltin() {
        assertEquals(MessagePreview.protocolFrames("HI THERE", true), MessagePreview.previewFrames("", false))
        assertEquals(
            MessagePreview.previewFrames("hi", false) + MessagePreview.previewFrames("hi", false),
            MessagePreview.played(GestureMessage("hi", scroll = false, msPerStep = 500, plays = 2)),
        )
    }

    @Test fun showNowIsStaticUntilItOverflows() {
        assertEquals(listOf("HI   "), MessagePreview.showNow("hi"))
        assertEquals(listOf("HELLO"), MessagePreview.showNow("hello"))
        assertEquals(MessagePreview.protocolFrames("hello!", true), MessagePreview.showNow("hello!"))
        assertTrue(MessagePreview.capped("x".repeat(100), true))
        assertFalse(MessagePreview.capped("hi", false))
    }

    @Test fun curveIsOddAndDeadzoneIsFlat() {
        assertEquals(0f, MouseCurve.response(0.05f, curve = 0, deadzoneFraction = 0.1f))
        assertEquals(1f, MouseCurve.response(1f, curve = 0, deadzoneFraction = 0f))
        assertEquals(-1f, MouseCurve.response(-1f, curve = 100, deadzoneFraction = 0f))
        val mid = MouseCurve.response(0.5f, curve = 100, deadzoneFraction = 0f)
        assertTrue(mid < 0.5f)
        val samples = MouseCurve.samples(curve = 50, deadzoneDeg = 4)
        assertEquals(49, samples.size)
        assertEquals(-1f, samples.first().first)
        assertEquals(1f, samples.last().first)
        assertEquals(0f, MouseCurve.pointerGain(0f, curve = 100, sensitivity = 100))
        assertEquals(1f, MouseCurve.pointerGain(1f, curve = 0, sensitivity = 100))
        assertTrue(MouseCurve.pointerGain(0.5f, curve = 100, sensitivity = 100) < 0.5f)
        val pointer = MouseCurve.pointerSamples(curve = 50, sensitivity = 80)
        assertEquals(0f, pointer.first().first)
        assertEquals(1f, pointer.last().first)
    }

    @Test fun firmwareOrdersCustomNewestAndStockLast() {
        val stock = FirmwareImage("stock.bin", "8fb5ee37" + "ab".repeat(28), "stock V2.1.4")
        val older = FirmwareImage("b11.bin", "b046ee55" + "cd".repeat(28), "custom build 11")
        val newer = FirmwareImage("b14.bin", "aaaaaaaa" + "ef".repeat(28), "custom build 14")
        val ordered = FirmwareCatalog.ordered(listOf(stock, older, newer))
        assertEquals(listOf("b14.bin", "b11.bin", "stock.bin"), ordered.map { it.fileName })
        assertEquals("Stock V2.1.4 (restore) · 8fb5ee37", FirmwareCatalog.menuLabel(stock))
        assertEquals("custom build 14 · aaaaaaaa", FirmwareCatalog.menuLabel(newer))
        val fake = FirmwareCatalog.ordered(FakeRingController().firmwareImages())
        assertEquals("custom build 11", fake.first().label)
        assertTrue(FirmwareCatalog.isStock(fake.last()))
    }

    @Test fun stockCommandsAreRawBodiesTheFakeAccepts() {
        val ring = FakeRingController()
        assertTrue(DevCommands.all.flatMap { it.bodies }.all { ring.sendRaw(it) == null })
        assertFalse(DevCommands.all.any { cmd -> cmd.bodies.any { it.replace(" ", "") == "02220002" } })
        assertEquals("02 34 00 01", DevCommands.all.first { it.label == "Find device" }.bodies.single())
        assertTrue(DevCommands.reset.bodies.size >= 8)
    }

    @Test fun actionRoundTrip() {
        val hook = GestureAction.Webhook(url = "https://example.test", method = "PUT", headers = mapOf("X-Ring" to "1"))
        assertEquals(ActionKind.Webhook, hook.kind())
        assertEquals("PUT https://example.test", hook.summary())
        assertEquals(hook, freshAction(ActionKind.Webhook, hook))
        assertEquals(GestureAction.ApiEventOnly, freshAction(ActionKind.ApiEventOnly, hook))
        assertEquals(mapOf("X-Ring" to "1"), parseHeaders(formatHeaders(hook.headers)))
        assertTrue(parseHeaders("not a header\n:missing").isEmpty())
    }

    @Test fun clockFormatsInTheGivenZone() {
        val ms = ZonedDateTime.of(2026, 11, 19, 22, 19, 0, 0, ZoneId.of("America/Denver")).toInstant().toEpochMilli()
        assertEquals("Nov 19, 10:19 PM", formatClock(ms, ZoneId.of("America/Denver"), Locale.US))
        assertEquals("Not synced yet", formatClock(null))
    }

    @Test fun statusDumpIncludesTheLink() {
        val text = statusDump(
            Connection(ConnectionState.CONNECTED, "SY01", "Connected"),
            RingStatus(batteryPercent = 76, mode = com.smartring.core.RingMode.CLOCK),
        )
        assertTrue(text.contains("connected"))
        assertTrue(text.contains("76"))
    }

    @Test fun segmentFontLightsAAndNotSpace() {
        assertFalse((0 until 7).any { y -> (0 until 5).any { x -> SegmentFont.isLit(' ', x, y) } })
        assertTrue(SegmentFont.isLit('A', 2, 0))
        assertTrue(SegmentFont.isLit('?', 1, 0))
    }
}
