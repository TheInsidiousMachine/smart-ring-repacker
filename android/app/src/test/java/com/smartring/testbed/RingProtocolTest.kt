package com.smartring.testbed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RingProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun crcCatalogueCheck() {
        assertEquals(0xBB3D, RingProtocol.crc16Arc("123456789".toByteArray()))
    }

    @Test fun tableMatchesLiveTriples() {
        assertEquals(162, CommandTable.size)
        assertEquals(0x12, CommandTable.resolve(2, 99, 16))
        assertEquals(0x1F, CommandTable.resolve(6, 9, 0))
        assertEquals(0x97, CommandTable.resolve(5, 26, 16))
        assertEquals(0x1C, CommandTable.resolve(5, 26, 48))
        assertNull(CommandTable.resolve(9, 9, 9))
    }

    @Test fun builders() {
        fun c(p: ByteArray) = p.copyOfRange(6, p.size).toList()
        assertEquals(bytes(2, 52, 0, 1).toList(), c(RingProtocol.findDevice()))
        assertEquals(bytes(6, 1, 0, 1).toList(), c(RingProtocol.cameraMode(true)))
        assertEquals(bytes(6, 18, 0, 2).toList(), c(RingProtocol.appForeground(false)))
        assertEquals(bytes(2, 112, 16).toList(), c(RingProtocol.queryFactoryState()))
    }

    @Test fun pushMessageTruncatesUtf8Safely() {
        val cal = Calendar.getInstance().apply { set(2026, 10, 18, 1, 2, 3) }
        val p = RingProtocol.pushMessage("é".repeat(20), "x".repeat(400), cal = cal)
        val c = p.copyOfRange(6, p.size)
        assertEquals(bytes(4, 1, 0, 127, 26, 11, 18, 1, 2, 3).toList(), c.copyOfRange(0, 10).toList())
        val title = String(c.copyOfRange(42, 74), Charsets.UTF_8).trimEnd('\u0000')
        assertEquals("é".repeat(16), title)
        assertEquals(74 + 250, c.size)
    }

    @Test fun assemblerJoinsFragments() {
        val content = bytes(5, 3, 48) + ByteArray(250) { it.toByte() }
        val pkt = RingProtocol.buildPacket(content)
        val a = RingProtocol.FrameAssembler()
        assertNull(a.feed(pkt.copyOfRange(0, 100)))
        val f = a.feed(pkt.copyOfRange(100, pkt.size))!!
        assertTrue(f.crcOk)
        assertEquals(content.toList(), f.content.toList())
    }

    @Test fun classifyEvents() {
        val tap = RingProtocol.classify(RingProtocol.Frame(1, bytes(6, 1, 0, 1)))
        assertEquals(RingProtocol.Event.ShutterTap(1), tap)
        assertTrue(RingProtocol.classify(RingProtocol.Frame(1, bytes(6, 1, 0, 1, 0))) is RingProtocol.Event.Setting)
        assertEquals(RingProtocol.Event.Music(3), RingProtocol.classify(RingProtocol.Frame(1, bytes(4, 2, 0, 3))))
        assertEquals(RingProtocol.Event.Count(7), RingProtocol.classify(RingProtocol.Frame(1, bytes(6, 16, 0, 7))))
        assertEquals(
            RingProtocol.Event.FactoryState(1, 0, 1),
            RingProtocol.classify(RingProtocol.Frame(1, bytes(2, 112, 16, 1, 0, 1)))
        )
    }

    @Test fun supportBitmap() {
        val p = ByteArray(95)
        p[2] = 1; p[6] = 1; p[29] = 2; p[80] = 1; p[82] = 1; p[90] = 1
        val s = RingProtocol.parseSupport(p)!!
        assertEquals(listOf("bo_reminder", "health_all_switch", "hr", "muslim_count", "take_photo", "video_hid"), s)
        val q = ByteArray(95).also { it[82] = 1 }
        assertFalse(RingProtocol.parseSupport(q)!!.contains("hr"))
        assertNull(RingProtocol.parseSupport(ByteArray(10)))
    }

    @Test fun tapClassifier() {
        val c = TapGestureClassifier(400)
        assertNull(c.tap(0)); assertNull(c.tap(200))
        assertNull(c.poll(500)); assertEquals("double", c.poll(601))
        assertNull(c.tap(1000)); assertEquals("single", c.tap(2000))
        assertNull(c.tap(2100)); assertNull(c.tap(2200)); assertEquals("triple", c.poll(3000))
    }

    @Test fun customFirmwareCommands() {
        val s = RingProtocol.customMouseSettings(RingProtocol.MOUSE_FLIP_Y or RingProtocol.MOUSE_SWAP, 300)
        // AB 01 len=5 crc crc | 7A 01 00 flags gain(clamped 250)
        assertEquals(listOf(0x7A, 0x01, 0x00, 6, 250), s.drop(6).map { it.toInt() and 0xFF })
        val t = RingProtocol.customText("hello world")
        assertEquals("HELLO", String(t.copyOfRange(9, t.size), Charsets.US_ASCII))
        assertEquals(8, ((t[2].toInt() and 0xFF) shl 8) or (t[3].toInt() and 0xFF))
    }

    @Test fun motionFrameParses() {
        // content: 7A 11 00 | seq=5 n=2 | (1,-2,2048) (3,4,-5)
        val content = byteArrayOf(0x7A, 0x11, 0, 5, 2, 1, 0, -2, -1, 0, 8, 3, 0, 4, 0, -5, -1)
        val ev = RingProtocol.classify(RingProtocol.Frame(1, content)) as RingProtocol.Event.Motion
        assertEquals(5, ev.seq)
        assertEquals(listOf(1, -2, 2048), ev.samples[0].toList())
        assertEquals(listOf(3, 4, -5), ev.samples[1].toList())
    }

    @Test fun gestureFramesParse() {
        val st = RingProtocol.classify(RingProtocol.Frame(1, byteArrayOf(0x7A, 0x12, 0, 1, 1, 4, 0, 9, 0x67, 0x20, 0, 3, 7))) as RingProtocol.Event.Setting
        assertTrue(st.text, st.text.endsWith("ctrl1=0x67 fifo=0x20 gesture=on fired=3 armed=7"))
        val ev = RingProtocol.classify(RingProtocol.Frame(1, byteArrayOf(0x7A, 0x13, 0, 2, 4, 9))) as RingProtocol.Event.Setting
        assertEquals("Gesture FIRED fired=4 armed=9", ev.text)
        assertEquals(listOf(0x7A, 0x05, 0x00, 0), RingProtocol.customGesture(false).drop(6).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x7A, 0x06, 0x00), RingProtocol.customGestureTest().drop(6).map { it.toInt() and 0xFF })
    }

    @Test fun gestureMessage() {
        assertEquals(listOf("FOUR ", " OFF ", "BUDDY"), RingProtocol.messageFrames("four off buddy", false).map { it })
        assertEquals(listOf("HELLO", "WORLD", "  !  "), RingProtocol.messageFrames("helloworld !", false))
        val sc = RingProtocol.messageFrames("HI", true)
        assertEquals(listOf("     ", "    H", "   HI", "  HI ", " HI  ", "HI   ", "I    ", "     "), sc)
        assertEquals(32, RingProtocol.messageFrames("x".repeat(100), true).size)
        val f = RingProtocol.customMessageFrames(4, listOf("HELLO", " YOU "))
        assertTrue(f.size <= 20)
        assertEquals(listOf(0x7A, 0x07, 0x00, 4) + "HELLO YOU ".map { it.code }, f.drop(6).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x7A, 0x08, 0x00, 3, 5, 2), RingProtocol.customMessageConfig(3, 100, 2).drop(6).map { it.toInt() and 0xFF })
    }

    @Test fun build12Commands() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 19, 23, 5, 9) }
        assertEquals(listOf(2, 1, 0, 26, 11, 19, 23, 5, 9), RingProtocol.setTime(c).drop(6).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x7A, 0x09, 0, 1), RingProtocol.customMode(1).drop(6).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x7A, 0x0A, 0, 50, 40, 4, 5, 35), RingProtocol.customMouse(50, 40, 4, 5, 700).drop(6).map { it.toInt() and 0xFF })
        val m = RingProtocol.classify(RingProtocol.Frame(1, byteArrayOf(0x7A, 0x14, 0, 1))) as RingProtocol.Event.Setting
        assertEquals("Ring mode mouse", m.text)
        val t = RingProtocol.classify(RingProtocol.Frame(1, byteArrayOf(0x7A, 0x15, 0, 3))) as RingProtocol.Event.Setting
        assertEquals("Ring touch right_click", t.text)
    }

    @Test fun build16KeysMode() {
        assertEquals(listOf(0x7A, 0x09, 0, 3), RingProtocol.customMode(3).drop(6).map { it.toInt() and 0xFF })
        val m = RingProtocol.classify(RingProtocol.Frame(1, byteArrayOf(0x7A, 0x14, 0, 3))) as RingProtocol.Event.Setting
        assertEquals("Ring mode keys", m.text)
        // press at ring tick 0x1234 = 4660 (build 16 appends the 20 ms tick, little-endian)
        val t = RingProtocol.classify(RingProtocol.Frame(1, byteArrayOf(0x7A, 0x15, 0, 1, 0x34, 0x12))) as RingProtocol.Event.Setting
        assertEquals("Ring touch press t=4660", t.text)
    }
}
