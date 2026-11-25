package com.smartring.core.example

import com.smartring.core.Connection
import com.smartring.core.ConnectionState
import com.smartring.core.FirmwareImage
import com.smartring.core.GestureMessage
import com.smartring.core.LogLine
import com.smartring.core.MotionBatch
import com.smartring.core.MouseSettings
import com.smartring.core.OtaProgress
import com.smartring.core.RecordingState
import com.smartring.core.RingCapability
import com.smartring.core.RingController
import com.smartring.core.RingEvent
import com.smartring.core.RingMode
import com.smartring.core.RingStatus
import com.smartring.core.TouchKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * TEMPLATE for supporting another ring. Copy this file, rename it, and replace the invented
 * "ExampleRing" wire format below with your ring's real protocol. Read docs/ADDING_A_RING.md first.
 *
 * It is a complete, compiling [RingController] built on a [RingTransport]:
 *  - supported here: connection state, battery query, clock sync, touch events, raw writes, log;
 *  - everything else follows the convention "unsupported = harmless no-op (or an error string for
 *    calls that return one) + a log line", and is excluded from [capabilities].
 *
 * Invented wire format (NOT a real device): [0xEE, cmd, len, payload..., xor of all previous bytes]
 *   phone -> ring  0x01 battery query            reply  0x01 [percent]
 *   phone -> ring  0x02 set time [yy mo dd hh mi ss]
 *   ring -> phone  0x10 touch  [0 = finger down, 1 = finger up after < 300 ms, 2 = finger up after a longer hold]
 *
 * The one thing external apps (e.g. the gaze keyboard) need from a ring is touch events:
 * finger down -> TouchKind.HOLD_START, then TAP (short) or HOLD_END (long) on release.
 */
class ExampleRingController(
    private val transport: RingTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) : RingController, RingTransport.Listener {

    /** Only advertise what is implemented. Mouse, display, motion, etc. are not supported by the template. */
    override val capabilities: Set<RingCapability> = setOf(
        RingCapability.TOUCH_EVENTS, RingCapability.BATTERY, RingCapability.CLOCK_SYNC, RingCapability.RAW_COMMANDS,
    )

    private val _connection = MutableStateFlow(Connection())
    override val connection: StateFlow<Connection> = _connection
    private val _status = MutableStateFlow(RingStatus())
    override val status: StateFlow<RingStatus> = _status
    private val _events = MutableSharedFlow<RingEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RingEvent> = _events
    private val _motion = MutableSharedFlow<MotionBatch>(extraBufferCapacity = 1)
    override val motion: SharedFlow<MotionBatch> = _motion          // never emits: no motion stream
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    override val log: StateFlow<List<LogLine>> = _log
    private val _message = MutableStateFlow(GestureMessage())
    override val gestureMessage: StateFlow<GestureMessage> = _message
    private val _mouse = MutableStateFlow(MouseSettings())
    override val mouseSettings: StateFlow<MouseSettings> = _mouse
    private val _recording = MutableStateFlow<RecordingState?>(null)
    override val recording: StateFlow<RecordingState?> = _recording
    private val _ota = MutableStateFlow<OtaProgress?>(null)
    override val ota: StateFlow<OtaProgress?> = _ota

    private var wantConnected = false

    init { transport.listener = this }

    private fun log(text: String) = _log.update { (it + LogLine(clock(), text)).takeLast(2000) }
    private fun unsupported(what: String) = log("$what: not supported by this ring")

    // ------------------------------------------------------------ connection
    override fun connect() {
        wantConnected = true
        _connection.value = Connection(ConnectionState.SCANNING, null, "Looking for ring…")
        transport.connect()
    }

    override fun disconnect() {
        wantConnected = false
        transport.disconnect()
        _connection.value = Connection()
    }

    override fun onConnected(deviceName: String) {
        _connection.value = Connection(ConnectionState.CONNECTED, deviceName, "Connected: $deviceName")
        send(Frame.BATTERY_QUERY, ByteArray(0))
        syncClock()
    }

    override fun onDisconnected() {
        _connection.value = Connection()
        // Contract: after an unexpected drop, keep retrying until disconnect() is called.
        if (wantConnected) connect()
    }

    // ------------------------------------------------------------ ring -> phone
    private val assembler = Frame.Assembler()

    override fun onNotify(data: ByteArray) {
        for (frame in assembler.feed(data)) handle(frame)
    }

    private fun handle(frame: Frame.Parsed) {
        when (frame.cmd) {
            Frame.BATTERY_QUERY -> frame.payload.firstOrNull()?.let { p ->
                _status.update { it.copy(batteryPercent = p.toInt() and 0xFF) }
            }
            Frame.TOUCH -> {
                val kind = when (frame.payload.firstOrNull()?.toInt()) {
                    0 -> TouchKind.HOLD_START
                    1 -> TouchKind.TAP
                    2 -> TouchKind.HOLD_END
                    else -> null
                }
                if (kind != null) _events.tryEmit(RingEvent.Touch(kind, clock()))
            }
            else -> _events.tryEmit(RingEvent.Info("unknown frame 0x%02x".format(frame.cmd), clock()))
        }
    }

    // ------------------------------------------------------------ phone -> ring
    private fun send(cmd: Int, payload: ByteArray) = transport.write(Frame.build(cmd, payload))

    override fun syncClock() {
        val c = java.util.Calendar.getInstance()
        send(Frame.SET_TIME, byteArrayOf(
            (c.get(java.util.Calendar.YEAR) - 2000).toByte(), (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(), c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(), c.get(java.util.Calendar.SECOND).toByte()))
        _status.update { it.copy(clockSyncedAtMs = clock()) }
    }

    override fun sendRaw(hex: String): String? {
        val bytes = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").toIntOrNull(16) ?: return "Not hex: \"$it\"" }
        if (bytes.isEmpty() || bytes.any { it !in 0..255 }) return "Need at least one hex byte"
        transport.write(ByteArray(bytes.size) { bytes[it].toByte() })
        return null
    }

    // ------------------------------------------------------------ unsupported: harmless no-ops
    override fun showText(text: String) = unsupported("showText")
    override fun setGestureMessage(message: GestureMessage) { _message.value = message; unsupported("gesture message") }
    override fun playGestureMessage() = unsupported("playGestureMessage")
    override fun setGestureDetector(on: Boolean) = unsupported("gesture detector")
    override fun setBrightness(level: Int) = unsupported("brightness")
    override fun setScreenTimeout(seconds: Int) = unsupported("screen timeout")
    override fun setMode(mode: RingMode) = unsupported("mode $mode")
    override fun setMouseSettings(settings: MouseSettings) { _mouse.value = settings; unsupported("mouse") }
    override fun setMotionStreaming(on: Boolean) { if (on) unsupported("motion streaming") }
    override fun findRing() = unsupported("findRing")
    override fun startRecording(label: String): String? = "Recording not supported by this ring"
    override fun stopRecording() {}
    override fun firmwareImages(): List<FirmwareImage> = emptyList()
    override fun startFirmwareUpdate(image: FirmwareImage, confirm: String): String? = "Firmware update not supported by this ring"
    override fun powerOff() = unsupported("powerOff")

    /** The invented example framing. Replace with your ring's real framing + checksum. */
    object Frame {
        const val BATTERY_QUERY = 0x01
        const val SET_TIME = 0x02
        const val TOUCH = 0x10
        private const val PREAMBLE = 0xEE

        class Parsed(val cmd: Int, val payload: ByteArray)

        fun build(cmd: Int, payload: ByteArray): ByteArray {
            val body = byteArrayOf(PREAMBLE.toByte(), cmd.toByte(), payload.size.toByte()) + payload
            return body + checksum(body).toByte()
        }

        fun checksum(bytes: ByteArray): Int = bytes.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }

        /** Reassembles frames that arrive split across notifications (or several in one).
         *  Returns every complete frame; drops frames with a bad checksum and resyncs on garbage. */
        class Assembler {
            private var buf = ByteArray(0)

            fun feed(data: ByteArray): List<Parsed> {
                buf += data
                val out = mutableListOf<Parsed>()
                while (true) {
                    val start = buf.indexOfFirst { (it.toInt() and 0xFF) == PREAMBLE }
                    if (start < 0) { buf = ByteArray(0); break }
                    if (start > 0) buf = buf.copyOfRange(start, buf.size)
                    if (buf.size < 3) break
                    val total = 3 + (buf[2].toInt() and 0xFF) + 1
                    if (buf.size < total) break
                    val frame = buf.copyOfRange(0, total)
                    buf = buf.copyOfRange(total, buf.size)
                    val body = frame.copyOfRange(0, total - 1)
                    if (checksum(body) == (frame[total - 1].toInt() and 0xFF)) {
                        out += Parsed(frame[1].toInt() and 0xFF, frame.copyOfRange(3, total - 1))
                    }
                }
                return out
            }
        }
    }
}
