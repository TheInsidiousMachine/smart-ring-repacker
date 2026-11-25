package com.smartring.testbed

import java.security.MessageDigest

/**
 * Stock SY01 OTA client (ff00/ff01), ported from pxi_ota.py and checked against
 * the firmware's handlers (docs/FIRMWARE_ANALYSIS.md "OTA updater path").
 *
 * Ring replies arrive two ways:
 *  - notify on ff01: OBJECT_CREATE ack [25], burst ack [17, sumLo, sumHi], UPGRADE ack [18]
 *  - value READ from ff01: INIT_NEW params [0E,10,27,...] and verify result [0E,02,18,00|FF]
 *
 * Safety: only the opcodes INIT_NEW/OBJECT_CREATE/UPGRADE/MCU_RESET + raw data are ever
 * produced; MCU_RESET is sent ONLY after the ring's verify result reads back OK, so a
 * failed or interrupted transfer leaves the running image in place.
 */
class PxiOtaSession(
    private val image: ByteArray,
    private val io: Io,
) {
    interface Io {
        fun writeCommand(bytes: ByteArray)
        fun writeData(bytes: ByteArray)
        /** Request a read of ff01; result must come back via [onRead]. */
        fun requestRead(delayMs: Long)
        fun log(msg: String)
    }

    enum class State { IDLE, WAIT_INIT_READ, WAIT_OBJECT_ACK, WAIT_BURST_ACK, WAIT_UPGRADE_ACK, WAIT_VERIFY_READ, DONE, FAILED }

    var state = State.IDLE; private set
    var failure: String? = null; private set
    var progress = 0f; private set

    private var maxObj = 0
    private var mtu = 0
    private var prn = 0
    private var sent = 0
    private var objSent = 0
    private var runningSum = 0

    fun start(maxChunk: Int) {
        require(image.isNotEmpty())
        chunkCap = maxChunk
        state = State.WAIT_INIT_READ
        io.writeCommand(byteArrayOf(OP_INIT_NEW) + le32(image.size))
        io.requestRead(1000) // stock app reads ff01 1 s after INIT_NEW
    }

    private var chunkCap = 182

    fun onRead(v: ByteArray) {
        when (state) {
            State.WAIT_INIT_READ -> {
                if (v.size < 18 || u8(v, 0) != 0x0E || u8(v, 2) != 0x27) return fail("bad INIT reply ${hex(v)}")
                maxObj = le32at(v, 9); mtu = le16at(v, 13); prn = le16at(v, 15)
                val ok = u8(v, 17)
                io.log("INIT: maxObj=$maxObj mtu=$mtu prn=$prn ok=$ok")
                if (ok != 1 || maxObj !in 256..(1 shl 20) || mtu !in 20..512 || prn !in 1..64) return fail("implausible INIT params")
                mtu = minOf(mtu, chunkCap)
                createObject()
            }
            State.WAIT_VERIFY_READ -> {
                if (v.size >= 4 && u8(v, 0) == 0x0E && u8(v, 2) == 0x18 && u8(v, 3) == 0x00) {
                    io.log("Ring verified image (size+sum OK). Sending reset.")
                    io.writeCommand(byteArrayOf(OP_MCU_RESET, 0))
                    state = State.DONE
                } else fail("ring rejected image: ${hex(v)} — NOT resetting; running firmware untouched")
            }
            else -> {}
        }
    }

    fun onNotify(v: ByteArray) {
        if (v.isEmpty()) return
        when (state) {
            State.WAIT_OBJECT_ACK -> if (u8(v, 0) == 0x25) { state = State.WAIT_BURST_ACK; burst() }
            State.WAIT_BURST_ACK -> if (u8(v, 0) == 0x17 && v.size >= 3) {
                val ack = le16at(v, 1)
                if (ack != runningSum) return fail("checksum mismatch ring=%04x ours=%04x".format(ack, runningSum))
                progress = sent.toFloat() / image.size
                when {
                    sent >= image.size -> {
                        state = State.WAIT_UPGRADE_ACK
                        io.writeCommand(byteArrayOf(OP_UPGRADE) + le32(image.size) + le16(sum16(image)))
                    }
                    objSent >= maxObj -> createObject()
                    else -> burst()
                }
            }
            State.WAIT_UPGRADE_ACK -> if (u8(v, 0) == 0x18) { state = State.WAIT_VERIFY_READ; io.requestRead(300) }
            else -> {}
        }
    }

    fun timeout() { if (state != State.DONE && state != State.FAILED && state != State.IDLE) fail("timeout in $state") }

    private fun createObject() {
        objSent = 0
        state = State.WAIT_OBJECT_ACK
        io.writeCommand(byteArrayOf(OP_OBJECT_CREATE) + le32(sent) + le32(maxObj))
    }

    private fun burst() {
        repeat(prn) {
            val n = minOf(mtu, image.size - sent, maxObj - objSent)
            if (n <= 0) return
            val chunk = image.copyOfRange(sent, sent + n)
            runningSum = sum16(chunk, runningSum)
            io.writeData(chunk)
            sent += n; objSent += n
        }
    }

    private fun fail(msg: String) { failure = msg; state = State.FAILED; io.log("OTA FAILED: $msg") }

    companion object {
        const val OP_INIT_NEW: Byte = 0x27
        const val OP_OBJECT_CREATE: Byte = 0x25
        const val OP_UPGRADE: Byte = 0x18
        const val OP_MCU_RESET: Byte = 0x22

        /** Only images whose SHA-256 is listed here can be sent. Add a hash only after
         *  the rollback checklist for that exact image is complete and the user has explicitly approved it. */
        val ALLOWED_SHA256 = mapOf(
            "8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50" to "stock V2.1.4 (30330000)",
            "1bfba75741a5e0a6b23c5d58205145fc73abe2e89e7b76393dc1470975eaa2c5" to "custom build 9: fast-sensor recorder; steps/sleep/SpO2 removed",
            "fa38d3fc3a0067a1b1e7d5dc71de8b880c5955dac33e025b85fcf13be25f7f09" to "custom build 10: bump-bump-flick detector (always on)",
            "b046ee55944839b748e8a8fe0b76502be6dabdd5da4d1e712b89c17aaabf23a1" to "custom build 11: gesture + custom message/speed",
            "60915df21ead44d0a0ee26efbc8b1c3004538e175de2fcfd8b032c9223def596" to "custom build 12: clock/mouse/heart touch modes, tilt mouse, no stock menu",
            "23c4ac21c17cf16cfdc9f154c4e56af60e2131332e0c60c635f6ebb11c7aadad" to "custom build 13: gesture v2 (rejects flicks/snaps/shaking) + build 12 features",
            "9e4d7a4ec23b5cf9c7cb3116a5bb97f3c8e1340eefd3b9862d9a4afb0dedbd98" to "custom build 15: fixes build 14 link bug, gentler pointer mouse, pulse-synced heart LED",
            "e32112739a1d890cab09f80290d3afa879bbf2575b75386904e1652d8953e5a1" to "custom build 16: keys mode for the gaze keyboard (fast link, stamped touch edges) + build 15",
        )

        fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun sum16(b: ByteArray, start: Int = 0): Int {
            var s = start
            for (x in b) s = (s + (x.toInt() and 0xFF)) and 0xFFFF
            return s
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        private fun le16at(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
        private fun le32at(b: ByteArray, i: Int) = le16at(b, i) or (le16at(b, i + 2) shl 16)
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        private fun hex(b: ByteArray) = RingProtocol.toHex(b)
    }
}
