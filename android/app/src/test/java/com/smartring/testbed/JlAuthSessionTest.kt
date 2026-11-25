package com.smartring.testbed

import org.junit.Assert.assertEquals
import org.junit.Test

class JlAuthSessionTest {
    // Fake crypto: 01 ‖ (in[1..16] xor 0x5A). Shape-only, not the vendor algorithm.
    private val enc: (ByteArray) -> ByteArray = { i -> byteArrayOf(1) + ByteArray(16) { (i[it + 1].toInt() xor 0x5A).toByte() } }
    private val rnd = byteArrayOf(0) + ByteArray(16) { it.toByte() }

    @Test fun happyPath() {
        val sent = ArrayList<ByteArray>()
        val s = JlAuthSession({ rnd }, enc) { sent += it }
        s.start()
        assertEquals(17, sent[0].size); assertEquals(0, sent[0][0].toInt())
        s.onData(enc(rnd))
        assertEquals(JlAuthSession.PASS.toList(), sent[1].toList())
        val chal = byteArrayOf(0) + ByteArray(16) { 9 }
        s.onData(chal)
        assertEquals(enc(chal).toList(), sent[2].toList())
        assertEquals(17, sent[2].size)
        s.onData(JlAuthSession.PASS)
        assertEquals(JlAuthSession.State.OK, s.state)
    }

    @Test fun badDeviceResponseFails() {
        val s = JlAuthSession({ rnd }, enc) {}
        s.start()
        s.onData(byteArrayOf(1) + ByteArray(16))
        assertEquals(JlAuthSession.State.FAILED, s.state)
    }

    @Test fun ignoresUnsolicited() {
        val s = JlAuthSession({ rnd }, enc) {}
        assertEquals(false, s.onData(byteArrayOf(0) + ByteArray(16)))
        assertEquals(JlAuthSession.State.IDLE, s.state)
    }
}
