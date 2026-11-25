package com.smartring.testbed

import java.util.Calendar
import java.util.UUID

/**
 * SY01 ring protocol (JieLi chip path) for the user's own ring.
 *
 * Provenance: [live] = observed on the user's ring; [static] = transcribed from
 * the stock RWFit app with jadx and NOT yet seen on the ring. See docs/PROTOCOL.md.
 */
object RingProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000a00a-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000b002-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000b003-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // JieLi RCSP service as used by the vendor OTA SDK (dd/a.java) [static]
    val OTA_SERVICE_UUID: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
    val OTA_WRITE_CHAR_UUID: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
    val OTA_NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

    private const val PREAMBLE: Byte = 0xAB.toByte()

    const val SENSOR_HEART_RATE = 3
    const val SENSOR_SPO2 = 9

    // Video HID modes for {2,100,0,mode,defaultOpen} [static; UI strings in TRingDeviceFragment.K0]
    const val VIDEO_HID_OFF = 0
    const val VIDEO_HID_SHORT_VIDEO = 1
    const val VIDEO_HID_BOOK = 2
    const val VIDEO_HID_MUSIC = 3

    const val MSG_TITLE_MAX = 32
    const val MSG_CONTENT_MAX = 250

    /** CRC-16/ARC: poly 0x8005 (reflected 0xA001), init 0x0000, no xorout. [live] */
    fun crc16Arc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun buildPacket(content: ByteArray, bulk: Boolean = false): ByteArray {
        val header = ByteArray(6)
        header[0] = PREAMBLE
        header[1] = if (bulk) 0x11 else 0x01
        header[2] = ((content.size shr 8) and 0xFF).toByte()
        header[3] = (content.size and 0xFF).toByte()
        val crc = crc16Arc(content)
        header[4] = ((crc shr 8) and 0xFF).toByte()
        header[5] = (crc and 0xFF).toByte()
        return header + content
    }

    fun buildRequest(group: Int, subcmd: Int, flag: Int, args: ByteArray = ByteArray(0)): ByteArray =
        buildPacket(byteArrayOf(group.toByte(), subcmd.toByte(), flag.toByte()) + args)

    private fun b(v: Boolean) = if (v) 1.toByte() else 0.toByte()

    // --- Touch → phone HID modes [static] ---
    fun setVideoHid(mode: Int, defaultOpen: Boolean = true): ByteArray {
        require(mode in 0..3) { "mode must be 0..3" }
        return buildRequest(2, 100, 0, byteArrayOf(mode.toByte(), b(defaultOpen)))
    }
    fun queryVideoHid(): ByteArray = buildRequest(2, 100, 16)

    // --- Our firmware (fw/ring_app), group 0x7A; stock firmware ignores it ---
    const val MOUSE_FLIP_X = 1
    const val MOUSE_FLIP_Y = 2
    const val MOUSE_SWAP = 4
    /** flags: MOUSE_FLIP_X/Y, MOUSE_SWAP; gainPct 10..250 (100 = default speed). */
    fun customMouseSettings(flags: Int, gainPct: Int): ByteArray =
        buildRequest(0x7A, 0x01, 0, byteArrayOf((flags and 7).toByte(), gainPct.coerceIn(10, 250).toByte()))
    /** Motion recorder: ring streams raw accelerometer samples (frames 7A 11). */
    /** odr: accel CTRL1 ODR code (4 = stock ~25/s; 6 = fast). */
    fun customRecord(on: Boolean, odr: Int = 4): ByteArray = buildRequest(0x7A, 0x04, 0, byteArrayOf(b(on), odr.toByte()))
    /** Bump-bump-flick detector on/off (build 10+; on after every ring boot). */
    fun customGesture(on: Boolean): ByteArray = buildRequest(0x7A, 0x05, 0, byteArrayOf(b(on)))
    /** Show the gesture message now (screen test). */
    fun customGestureTest(): ByteArray = buildRequest(0x7A, 0x06, 0, byteArrayOf())
    const val MSG_MAX_FRAMES = 32
    val MODES = listOf("clock", "mouse", "heart", "keys")
    val TOUCH = listOf("tap", "press", "release", "right_click", "five_taps", "long_hold")
    /** Message frames for the gesture: 5 characters each, uppercase.
     *  scroll = marquee one character per step; otherwise word by word (long words split). */
    fun messageFrames(text: String, scroll: Boolean): List<String> {
        val t = text.trim().uppercase().map { if (it.code in 0x20..0x7e) it else ' ' }.joinToString("")
        if (t.isEmpty()) return emptyList()
        if (scroll) {
            val padded = "     $t     "
            return (0..padded.length - 5).map { padded.substring(it, it + 5) }.take(MSG_MAX_FRAMES)
        }
        return t.split(Regex(" +")).flatMap { w -> w.chunked(5) }
            .map { w -> val pad = 5 - w.length; " ".repeat(pad / 2) + w + " ".repeat(pad - pad / 2) }
            .take(MSG_MAX_FRAMES)
    }
    /** Upload frames starting at index [start] (≤2 per packet keeps it in one 20-byte BLE write). */
    fun customMessageFrames(start: Int, frames: List<String>): ByteArray =
        buildRequest(0x7A, 0x07, 0, byteArrayOf(start.toByte()) + frames.joinToString("") { it.padEnd(5).take(5) }.toByteArray(Charsets.US_ASCII))
    /** Use frames 0..n-1, each shown msPerFrame (20 ms steps), played reps times. n = 0 -> built-in message. */
    fun customMessageConfig(n: Int, msPerFrame: Int, reps: Int): ByteArray =
        buildRequest(0x7A, 0x08, 0, byteArrayOf(n.coerceIn(0, MSG_MAX_FRAMES).toByte(),
            (msPerFrame / 20).coerceIn(1, 255).toByte(), reps.coerceIn(1, 20).toByte()))
    /** Stock clock set (group 2 key 1): [year-2000, month, day, hour, min, sec], local time. */
    fun setTime(cal: java.util.Calendar = java.util.Calendar.getInstance()): ByteArray =
        buildRequest(2, 1, 0, byteArrayOf((cal.get(java.util.Calendar.YEAR) - 2000).toByte(),
            (cal.get(java.util.Calendar.MONTH) + 1).toByte(), cal.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            cal.get(java.util.Calendar.HOUR_OF_DAY).toByte(), cal.get(java.util.Calendar.MINUTE).toByte(),
            cal.get(java.util.Calendar.SECOND).toByte()))
    /** Build 12+: 0 clock, 1 mouse, 2 heart. */
    fun customMode(mode: Int): ByteArray = buildRequest(0x7A, 0x09, 0, byteArrayOf(mode.toByte()))
    /** Build 12+ tilt mouse: speed 0..100 (0 = clicker only), curve 0..100, deadzone degrees, flags (1 invX, 2 invY, 4 swap), right-click hold ms. */
    fun customMouse(speed: Int, curve: Int, deadzoneDeg: Int, flags: Int, rightClickMs: Int): ByteArray =
        buildRequest(0x7A, 0x0A, 0, byteArrayOf(speed.coerceIn(0, 100).toByte(), curve.coerceIn(0, 100).toByte(),
            deadzoneDeg.coerceIn(0, 20).toByte(), (flags and 15).toByte(), (rightClickMs / 20).coerceIn(10, 255).toByte()))
    fun customMouseRecenter(): ByteArray = buildRequest(0x7A, 0x0B, 0, byteArrayOf())
    /** Live heartbeat mode: heart LED flashes per beat, screen shows BPM updated every beat. */
    fun customHeart(on: Boolean): ByteArray = buildRequest(0x7A, 0x03, 0, byteArrayOf(b(on)))
    /** Show up to 5 ASCII characters on the ring screen right now (wakes the screen). */
    fun customText(text: String): ByteArray {
        val ascii = text.uppercase().map { if (it.code in 0x20..0x7e) it.code.toByte() else 0x20 }.take(5)
        return buildRequest(0x7A, 0x02, 0, ascii.toByteArray())
    }

    // --- Display [static set, live query] ---
    fun setLedLevel(open: Boolean, level: Int): ByteArray =
        buildRequest(2, 102, 0, byteArrayOf(b(open), level.toByte()))
    fun queryLedLevel(): ByteArray = buildRequest(2, 102, 16)
    fun setScreenTimeout(seconds: Int): ByteArray = buildRequest(2, 8, 0, byteArrayOf(seconds.toByte()))
    fun queryScreenTimeout(): ByteArray = buildRequest(2, 8, 16)

    // --- Haptics ---
    /** Vibration *preference* (level, count) — "getVibrationCountCmdJL". Not a buzz-now trigger. [static] */
    fun setVibrationPref(level: Int, count: Int): ByteArray =
        buildRequest(2, 11, 0, byteArrayOf(level.toByte(), count.toByte()))
    fun queryVibration(): ByteArray = buildRequest(2, 11, 16)
    /** Stock "find device" (获取查找设备) — the closest thing to buzz-now. [static] */
    fun findDevice(): ByteArray = buildRequest(2, 52, 0, byteArrayOf(1))

    // --- Wear hand [static set, live query] ---
    fun setWearHand(rightHand: Boolean): ByteArray = buildRequest(2, 104, 0, byteArrayOf(b(rightHand)))
    fun queryWearHand(): ByteArray = buildRequest(2, 104, 16)

    // --- Factory self-test state: [gsensor, heart, wear] — read-only query [static] ---
    /** WARNING (live 2026-11-18): appears to start the ring's factory self-test (PPG LED on, UI stuck). Dev only. */
    fun queryFactoryState(): ByteArray = buildRequest(2, 112, 16)
    /** Stock DebugActivity "getRingFactoryWCmdJL 0/1": factory mode off/on. Query = {2,103,16}. */
    fun setFactoryMode(on: Boolean): ByteArray = buildRequest(2, 103, 0, byteArrayOf(if (on) 1 else 0))
    fun queryFactoryMode(): ByteArray = buildRequest(2, 103, 16)

    // --- Tap event sources [static] ---
    /** Remote-shutter mode: ring taps come back as {6,1,0,x} (4-byte content). */
    fun cameraMode(enter: Boolean): ByteArray = buildRequest(6, 1, 0, byteArrayOf(b(enter)))
    /** Counter (赞念) mode; counts come back as {6,16,0,n} / {6,17,0,n}. */
    fun setCountMode(on: Boolean): ByteArray = buildRequest(2, 120, 0, byteArrayOf(b(on)))
    /** App foreground(1)/background(2) notice. Formerly mislabeled "arm tap reporting". */
    fun appForeground(foreground: Boolean): ByteArray =
        buildRequest(6, 18, 0, byteArrayOf(if (foreground) 1 else 2))

    // --- Sensors ---
    fun startMeasurement(sensorType: Int): ByteArray =
        buildRequest(6, 9, 0, byteArrayOf(sensorType.toByte(), 5, 1))   // [live ack]
    fun stopMeasurement(sensorType: Int): ByteArray =
        buildRequest(6, 9, 0, byteArrayOf(sensorType.toByte(), 5, 0))
    fun syncSteps(): ByteArray = buildRequest(5, 26, 16)                 // [live]
    fun queryDeviceSupport(): ByteArray = buildRequest(2, 99, 16)        // [live]
    /** {2,3,16} → [percent, voltage_mV(BE16)] (stock m5.b.G; stock OTA requires ≥30%). */
    fun queryBattery(): ByteArray = buildRequest(2, 3, 16)
    /** Stock ring-settings "Power off" = {2,34,0,1}. ({2,34,0,2} is factory RESTORE — never sent.) */
    fun powerOff(): ByteArray = buildRequest(2, 34, 0, byteArrayOf(1))
    fun queryFirmwareVersion(): ByteArray = buildRequest(2, 4, 16)       // [live query; layout m5/b.java:315]

    /** Truncate to ≤ limit UTF-8 bytes without splitting a code point. */
    fun utf8Truncate(text: String, limit: Int): ByteArray {
        val all = text.toByteArray(Charsets.UTF_8)
        if (all.size <= limit) return all
        var end = limit
        // back off over continuation bytes (10xxxxxx)
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
        return all.copyOfRange(0, end)
    }

    /** JL message push (lc.A) [static]. */
    fun pushMessage(
        title: String,
        content: String,
        appId: String = "com.android.mms",
        cal: Calendar = Calendar.getInstance(),
    ): ByteArray {
        val head = byteArrayOf(
            4, 1, 0, 127,
            (cal.get(Calendar.YEAR) % 100).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
        )
        val app = utf8Truncate(appId, 32).copyOf(32)
        val ttl = utf8Truncate(title, MSG_TITLE_MAX).copyOf(32)
        val body = utf8Truncate(content, MSG_CONTENT_MAX)
        return buildPacket(head + app + ttl + body)
    }

    data class Frame(val protocolFlag: Int, val content: ByteArray, val crcOk: Boolean = true) {
        val group: Int get() = content[0].toInt() and 0xFF
        val key: Int get() = content[1].toInt() and 0xFF
        val keyFlag: Int get() = content[2].toInt() and 0xFF
        val payload: ByteArray get() = content.copyOfRange(3, content.size)
        val resolvedId: Int? get() = CommandTable.resolve(group, key, keyFlag)
    }

    /** Reassembles headered first fragment + raw continuation fragments. */
    class FrameAssembler {
        private var buf = ByteArray(0)
        private var need = 0
        private var flag = 0
        private var crc = 0

        fun feed(data: ByteArray): Frame? {
            if (need == 0) {
                if (data.size < 6 || data[0] != PREAMBLE) return null
                flag = data[1].toInt() and 0xFF
                need = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                crc = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                buf = data.copyOfRange(6, data.size)
            } else {
                buf += data
            }
            if (buf.size < need) return null
            val content = buf.copyOfRange(0, need)
            need = 0
            buf = ByteArray(0)
            if (content.size < 3) return null
            return Frame(flag, content, crc16Arc(content) == crc)
        }

        fun reset() { need = 0; buf = ByteArray(0) }
    }

    /** Single-fragment parse (kept for callers that don't need reassembly). */
    fun parseFrame(data: ByteArray): Frame? {
        if (data.size < 6 || data[0] != PREAMBLE) return null
        val contentLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (data.size < 6 + contentLen || contentLen < 3) return null
        val content = data.copyOfRange(6, 6 + contentLen)
        val crc = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        return Frame(data[1].toInt() and 0xFF, content, crc16Arc(content) == crc)
    }

    sealed class Event {
        data class ShutterTap(val value: Int) : Event()
        data class Count(val value: Int) : Event()
        /** Ring touch in Music mode [live: value 3 seen]; stock map 2 play/pause, 3 next, 4 prev, 5 vol+, 6 vol-. */
        data class Music(val value: Int) : Event()
        data class Setting(val text: String) : Event()
        data class Support(val supported: List<String>) : Event()
        data class FactoryState(val gsensor: Int, val heart: Int, val wear: Int) : Event()
        data class Unknown(val text: String) : Event()
        /** Our firmware: raw accelerometer samples (raw>>2 units, ~2048 per g), 50 Hz. */
        class Motion(val seq: Int, val samples: List<IntArray>) : Event()
        /** Our firmware: raw PPG batch (logged for tuning). */
        class Ppg(val count: Int, val bpm: Int) : Event()
    }

    private val supportFields = mapOf(
        0 to "address_book", 1 to "msg_notification", 2 to "take_photo", 3 to "dnd",
        4 to "led_light", 5 to "wear_dir", 6 to "video_hid", 7 to "health_monitor",
        8 to "set_bt_name", 9 to "find_device", 10 to "recovery", 11 to "power_off",
        12 to "video_hid_book", 13 to "video_hid_music", 14 to "video_hid_android_book",
        15 to "child_app_switch", 16 to "push_msg_enable_switch",
        21 to "girl_care", 22 to "alarm", 23 to "bright_screen_time",
        24 to "default_en_system", 25 to "default_no_view_workout", 26 to "new_sport",
        27 to "app_front_and_back", 28 to "remember_switch",
        30 to "bright_screen", 31 to "motor_vibration_level",
    )
    private val healthFields = mapOf(
        81 to "step", 82 to "hr", 83 to "blood_pressure", 84 to "sleep",
        86 to "blood_oxygen", 87 to "hrv", 88 to "stress", 89 to "blood_sugar",
        90 to "muslim_count", 91 to "body_temp",
    )

    /** analysisDeviceSupportV2 layout [static]; null if payload too short. */
    fun parseSupport(payload: ByteArray): List<String>? {
        if (payload.size < 91) return null
        fun bit(off: Int, n: Int = 0) = ((payload[off].toInt() shr n) and 1) == 1
        val out = supportFields.filter { bit(it.key) }.values.toMutableList()
        if (bit(29, 0)) out += "hr_reminder"
        if (bit(29, 1)) out += "bo_reminder"
        if (bit(80)) {
            out += "health_all_switch"
            out += healthFields.filter { it.key < payload.size && bit(it.key) }.values
        }
        return out.sorted()
    }

    fun classify(f: Frame): Event {
        val p = f.payload
        fun u(i: Int) = p[i].toInt() and 0xFF
        return when {
            f.group == 0x7A && f.key == 0x11 && p.size >= 2 -> {
                val n = minOf(u(1), (p.size - 2) / 6)
                fun s16(i: Int) = (p[i].toInt() and 0xFF) or (p[i + 1].toInt() shl 8)
                Event.Motion(u(0), List(n) { k -> val o = 2 + 6 * k; intArrayOf(s16(o), s16(o + 2), s16(o + 4)) })
            }
            f.group == 0x7A && f.key == 0x10 && p.size >= 3 -> Event.Ppg(u(0), u(2))
            f.group == 0x7A && f.key == 0x12 && p.size >= 5 ->
                Event.Setting("Recorder status: on=${u(0)} timer=${u(1)} lastRead=${if (u(2) == 255) "-" else u(2).toString()} hidMode=${u(3)} seq=${u(4)}" +
                    (if (p.size >= 7) " ctrl1=0x%02x fifo=0x%02x%s".format(u(5), u(6), if (u(6) and 0x40 != 0) " OVERRUN" else "") else "") +
                    (if (p.size >= 10) " gesture=${if (u(7) and 1 != 0) "off" else "on"} fired=${u(8)} armed=${u(9)}" else "") +
                    (if (p.size >= 13) " msg=${u(10)}x${u(11) * 20}ms*${u(12)}" else "") +
                    (if (p.size >= 14) " mode=${MODES.getOrElse(u(13)) { u(13).toString() }}" else "") +
                    (if (p.size >= 15) " build=${u(14)}" else ""))
            f.group == 0x7A && f.key == 0x14 && p.isNotEmpty() -> Event.Setting("Ring mode ${MODES.getOrElse(u(0)) { u(0).toString() }}")
            f.group == 0x7A && f.key == 0x15 && p.isNotEmpty() -> Event.Setting("Ring touch ${TOUCH.getOrElse(u(0)) { u(0).toString() }}" + (if (p.size >= 3) " t=${u(1) or (u(2) shl 8)}" else ""))
            f.group == 0x7A && f.key == 0x13 && p.size >= 3 ->
                Event.Setting(if (u(0) == 2) "Gesture FIRED fired=${u(1)} armed=${u(2)}" else "Gesture armed fired=${u(1)} armed=${u(2)}")
            f.group == 6 && f.key == 1 && f.keyFlag == 0 ->
                if (f.content.size == 4) Event.ShutterTap(u(0)) else Event.Setting("camera-mode ack ${toHex(p)}")
            f.group == 4 && f.key == 2 && f.keyFlag == 0 && f.content.size == 4 -> Event.Music(u(0))
            f.group == 6 && (f.key == 16 || f.key == 17) && f.keyFlag == 0 && p.isNotEmpty() -> Event.Count(u(0))
            f.group == 2 && f.key == 99 && f.keyFlag == 16 ->
                parseSupport(p)?.let { Event.Support(it) } ?: Event.Unknown("short support map ${toHex(p)}")
            f.group == 2 && f.key == 112 && f.keyFlag == 16 && p.size >= 3 -> Event.FactoryState(u(0), u(1), u(2))
            f.group == 2 && f.key == 4 && f.keyFlag == 16 && p.size >= 3 ->
                Event.Setting("Firmware v${u(0)}.${u(1)}.${u(2)}" +
                    if (p.size >= 16) " model=${String(p.copyOfRange(8, 16)).trim('\u0000')}" else "")
            f.group == 2 && f.key == 100 && p.size >= 2 -> Event.Setting("Video HID mode=${u(0)} defaultOpen=${u(1) == 1}")
            f.group == 2 && f.key == 102 && p.size >= 2 -> Event.Setting("LED open=${u(0) == 1} level=${u(1)}")
            f.group == 2 && f.key == 8 && p.isNotEmpty() -> Event.Setting("Screen-on time ${u(0)}s")
            f.group == 2 && f.key == 11 && p.size >= 2 -> Event.Setting("Vibration pref level=${u(0)} count=${u(1)}")
            f.group == 2 && f.key == 104 && p.isNotEmpty() -> Event.Setting("Wear hand ${if (u(0) == 1) "RIGHT" else "LEFT"}")
            f.group == 2 && f.key == 3 && f.keyFlag == 16 && p.isNotEmpty() ->
                Event.Setting("Battery ${u(0)}%" + if (p.size >= 3) " ${(u(1) shl 8) or u(2)} mV" else "")
            f.group == 2 && f.key == 103 && p.isNotEmpty() -> Event.Setting("Factory mode=${u(0)}")
            f.group == 2 && f.key == 120 && p.isNotEmpty() -> Event.Setting("Counter mode=${u(0)}")
            else -> Event.Unknown(
                "(${f.group},${f.key},${f.keyFlag}) id=${f.resolvedId?.let { "0x%02X".format(it) } ?: "?"} ${toHex(p)}"
            )
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}

/** Groups discrete tap timestamps into single/double/triple/multiN gestures. */
class TapGestureClassifier(private val windowMs: Long = 450) {
    private val taps = ArrayList<Long>()

    fun tap(tMs: Long): String? {
        val done = poll(tMs)
        taps += tMs
        return done
    }

    fun poll(nowMs: Long): String? {
        if (taps.isNotEmpty() && nowMs - taps.last() > windowMs) {
            val n = taps.size
            taps.clear()
            return when (n) { 1 -> "single"; 2 -> "double"; 3 -> "triple"; else -> "multi$n" }
        }
        return null
    }
}
