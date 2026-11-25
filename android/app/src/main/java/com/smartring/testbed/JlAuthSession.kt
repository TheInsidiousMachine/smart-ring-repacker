package com.smartring.testbed

/**
 * JieLi RCSP auth handshake as the vendor SDK performs it (docs/FIRMWARE_ANALYSIS.md, "Why this is probably not the JieLi RCSP path").
 * Transport-compatibility check ONLY — it never sends RCSP commands or OTA data.
 * Crypto is injected (production: a vendor native library you supply yourself, see THIRD_PARTY.md;
 * the SY01 has no ae00 service, so this probe does nothing on it).
 */
class JlAuthSession(
    private val random17: () -> ByteArray,          // 00 ‖ 16 random
    private val encrypt17: (ByteArray) -> ByteArray, // 17 B in → 01 ‖ 16 B
    private val send: (ByteArray) -> Unit,
) {
    enum class State { IDLE, WAIT_DEVICE_RESPONSE, WAIT_DEVICE_CHALLENGE, WAIT_DEVICE_PASS, OK, FAILED }

    var state = State.IDLE; private set
    var failure: String? = null; private set
    private var myRandom: ByteArray? = null

    fun start() {
        val r = random17()
        require(r.size == 17 && r[0] == 0.toByte()) { "unexpected random format" }
        myRandom = r
        state = State.WAIT_DEVICE_RESPONSE
        send(r)
    }

    /** Feed a raw ae02 notification. Returns true if it was consumed. */
    fun onData(d: ByteArray): Boolean {
        val valid = (d.size == 5 && d[0] == 2.toByte()) ||
            (d.size == 17 && (d[0] == 0.toByte() || d[0] == 1.toByte()))
        if (!valid || state in setOf(State.IDLE, State.OK, State.FAILED)) return false
        when (state) {
            State.WAIT_DEVICE_RESPONSE -> {
                if (d.size != 17 || d[0] != 1.toByte()) return fail("expected 01-response, got ${RingProtocol.toHex(d)}")
                if (!encrypt17(myRandom!!).contentEquals(d)) return fail("device response did not verify")
                send(PASS)
                state = State.WAIT_DEVICE_CHALLENGE
            }
            State.WAIT_DEVICE_CHALLENGE -> {
                if (d.size != 17 || d[0] != 0.toByte()) return fail("expected 00-challenge, got ${RingProtocol.toHex(d)}")
                send(encrypt17(d))
                state = State.WAIT_DEVICE_PASS
            }
            State.WAIT_DEVICE_PASS -> {
                if (!d.contentEquals(PASS)) return fail("expected pass, got ${RingProtocol.toHex(d)}")
                state = State.OK
            }
            else -> return false
        }
        return true
    }

    fun timeout() { if (state !in setOf(State.IDLE, State.OK, State.FAILED)) fail("timeout in $state") }

    private fun fail(msg: String): Boolean { failure = msg; state = State.FAILED; return true }

    companion object {
        val PASS = byteArrayOf(0x02, 0x70, 0x61, 0x73, 0x73) // 02 "pass", 5 bytes
    }
}
