package com.smartring.testbed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulated ring modeled on the V2.1.4 handlers (notify vs read-back replies). */
class PxiOtaTest {
    private class FakeRing(val maxObj: Int = 4096, val mtu: Int = 180, val prn: Int = 4, val acceptVerify: Boolean = true) {
        val flash = java.io.ByteArrayOutputStream()
        var size = 0; var sum = 0; var objLeft = 0; var inBurst = 0
        var readValue = ByteArray(0)
        var reset = false
        val notifies = ArrayDeque<ByteArray>()

        fun cmd(c: ByteArray) {
            when (c[0].toInt() and 0xFF) {
                0x27 -> {
                    size = (c[1].toInt() and 0xFF) or ((c[2].toInt() and 0xFF) shl 8) or ((c[3].toInt() and 0xFF) shl 16)
                    readValue = byteArrayOf(0x0E, 0x10, 0x27, 0, 1, 0, 0, 0, 0,
                        maxObj.toByte(), (maxObj shr 8).toByte(), 0, 0, mtu.toByte(), (mtu shr 8).toByte(), prn.toByte(), 0, 1)
                }
                0x25 -> { objLeft = maxObj; inBurst = 0; notifies += byteArrayOf(0x25) }
                0x18 -> {
                    notifies += byteArrayOf(0x18)
                    val ok = acceptVerify && flash.size() == size
                    readValue = byteArrayOf(0x0E, 0x02, 0x18, if (ok) 0 else 0xFF.toByte())
                }
                0x22 -> reset = true
                else -> error("unexpected opcode ${c[0]}")
            }
        }

        fun data(d: ByteArray) {
            require(d.size <= mtu)
            flash.write(d); objLeft -= d.size; inBurst++
            sum = PxiOtaSession.sum16(d, sum)
            if (inBurst >= prn || objLeft <= 0 || flash.size() >= size) {
                inBurst = 0
                notifies += byteArrayOf(0x17, sum.toByte(), (sum shr 8).toByte())
            }
        }
    }

    private fun run(img: ByteArray, ring: FakeRing): PxiOtaSession {
        var pendingRead = false
        lateinit var s: PxiOtaSession
        s = PxiOtaSession(img, object : PxiOtaSession.Io {
            override fun writeCommand(bytes: ByteArray) = ring.cmd(bytes)
            override fun writeData(bytes: ByteArray) = ring.data(bytes)
            override fun requestRead(delayMs: Long) { pendingRead = true }
            override fun log(msg: String) {}
        })
        s.start(182)
        var guard = 0
        while (s.state != PxiOtaSession.State.DONE && s.state != PxiOtaSession.State.FAILED && guard++ < 100000) {
            if (pendingRead) { pendingRead = false; s.onRead(ring.readValue); continue }
            val n = ring.notifies.removeFirstOrNull() ?: break
            s.onNotify(n)
        }
        return s
    }

    @Test fun roundTripVariousSizes() {
        for (size in listOf(1, 180, 4096, 4097, 174952)) {
            val img = ByteArray(size) { (it * 31 + 7).toByte() }
            val ring = FakeRing()
            val s = run(img, ring)
            assertEquals("size $size", PxiOtaSession.State.DONE, s.state)
            assertTrue(ring.flash.toByteArray().contentEquals(img))
            assertTrue(ring.reset)
        }
    }

    @Test fun rejectedVerifyNeverResets() {
        val ring = FakeRing(acceptVerify = false)
        val s = run(ByteArray(5000) { it.toByte() }, ring)
        assertEquals(PxiOtaSession.State.FAILED, s.state)
        assertFalse(ring.reset)
    }

    @Test fun stockHashAllowlisted() {
        assertTrue(PxiOtaSession.ALLOWED_SHA256.containsKey("8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50"))
        // exactly: stock V2.1.4 (recovery) + custom build 3 (fw/ring_app/build_image.py)
        assertEquals(setOf(
            "8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50",
            "1bfba75741a5e0a6b23c5d58205145fc73abe2e89e7b76393dc1470975eaa2c5",
            "fa38d3fc3a0067a1b1e7d5dc71de8b880c5955dac33e025b85fcf13be25f7f09",
            "b046ee55944839b748e8a8fe0b76502be6dabdd5da4d1e712b89c17aaabf23a1",
            "60915df21ead44d0a0ee26efbc8b1c3004538e175de2fcfd8b032c9223def596",
            "23c4ac21c17cf16cfdc9f154c4e56af60e2131332e0c60c635f6ebb11c7aadad",
            "9e4d7a4ec23b5cf9c7cb3116a5bb97f3c8e1340eefd3b9862d9a4afb0dedbd98",
            "e32112739a1d890cab09f80290d3afa879bbf2575b75386904e1652d8953e5a1",
        ), PxiOtaSession.ALLOWED_SHA256.keys)
    }
}
