package com.smartring.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * THE INTEGRATION SEAM. The UI (com.smartring.ui), the gesture/action/API module
 * (com.smartring.gestures, com.smartring.api) and the BLE implementation (RealRingController,
 * wrapping RingLink) all meet here. To support another ring, implement [RingController]
 * (see docs/ADDING_A_RING.md and core/example/ExampleRingController.kt). Treat changes to this
 * file as API changes: the HTTP/WebSocket API and external projects depend on the event shapes.
 *
 * Everything is main-thread-safe to call; implementations do their own threading.
 */

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class Connection(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val detail: String = "Not connected",
)

/**
 * What the ring is doing right now (our firmware build 12+ reports this; stock firmware and other
 * rings stay UNKNOWN). KEYS (build 16+): button mode for an external keyboard app (see
 * docs/INTEGRATION.md): touch edges only, fast link, no HID, no shortcuts.
 */
enum class RingMode { UNKNOWN, CLOCK, MOUSE, HEART, KEYS }

data class RingStatus(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    /** e.g. "2.1.4" */
    val firmwareVersion: String? = null,
    /** Our custom build label, e.g. "build 12 (b046ee55)", null on stock firmware. */
    val customBuild: String? = null,
    val mode: RingMode = RingMode.UNKNOWN,
    /** Wall-clock millis of the last successful clock sync, null if never. */
    val clockSyncedAtMs: Long? = null,
    val gestureDetectorOn: Boolean? = null,
    /** Bump-bump-flick fires / arms since ring boot. */
    val gestureFired: Int = 0,
    val gestureArmed: Int = 0,
    /** Live BPM while in heart mode. */
    val heartBpm: Int? = null,
    val screenBrightness: Int? = null,      // 0..3
    val screenTimeoutSec: Int? = null,
)

/** Things that happen on the ring, pushed as they occur. */
sealed interface RingEvent {
    val atMs: Long
    /** ringTick: the ring's own 20 ms tick of the edge (build 16+, wraps at 65536), free of BLE jitter. */
    data class Touch(val kind: TouchKind, override val atMs: Long, val ringTick: Int? = null) : RingEvent
    data class ModeChanged(val mode: RingMode, override val atMs: Long) : RingEvent
    data class GestureArmed(override val atMs: Long) : RingEvent
    /** The on-ring bump-bump-flick fired. */
    data class GestureFired(override val atMs: Long) : RingEvent
    data class HeartBeat(val bpm: Int, override val atMs: Long) : RingEvent
    /** Free-text status/diagnostic line from the ring (shown in dev mode). */
    data class Info(val text: String, override val atMs: Long) : RingEvent
}

/**
 * Touch-pad edges and gestures. The firmware sends HOLD_START on every press and TAP *or*
 * HOLD_END on release (TAP if the press lasted < 300 ms, else HOLD_END). RIGHT_CLICK, FIVE_TAPS
 * and LONG_HOLD are firmware-recognised gestures (mouse right click, mode toggles).
 * A ring port must emit HOLD_START and TAP/HOLD_END to work as a button for external apps.
 */
enum class TouchKind { TAP, HOLD_START, HOLD_END, RIGHT_CLICK, FIVE_TAPS, LONG_HOLD }

/** Raw accelerometer batch, ~100 Hz, units raw>>2 (≈2048 per g), axes as the chip reports them. */
class MotionBatch(val seq: Int, val samples: List<IntArray>, val receivedAtMs: Long)

/** Message the ring plays on bump-bump-flick. Blank text = the firmware's built-in message
 * ("HI THERE" scrolling once, in firmware built from this source). */
data class GestureMessage(
    val text: String = "HI THERE",
    val scroll: Boolean = true,
    val msPerStep: Int = 160,      // 60..1500
    val plays: Int = 1,            // 1..5
)

/** How tilting the hand moves the cursor (firmware build 14+). */
enum class MouseStyle {
    /** Cursor follows changes in hand angle and stops when the hand stops (default, no drift). */
    POINTER,
    /** Holding the hand tilted keeps the cursor moving; further tilt = faster (build 12/13 behavior). */
    JOYSTICK,
}

/** Tilt mouse tuning. */
data class MouseSettings(
    val style: MouseStyle = MouseStyle.POINTER,
    val speed: Int = 50,           // 1..100 sensitivity
    val curve: Int = 50,           // 0 = linear .. 100 = strongly accelerating
    val deadzoneDeg: Int = 4,      // 0..15, joystick only
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val swapAxes: Boolean = false,
    val rightClickHoldMs: Int = 700,
)

data class LogLine(val atMs: Long, val text: String)

data class RecordingState(val label: String, val samples: Int, val samplesPerSec: Double, val gaps: Int, val file: String)

data class FirmwareImage(val fileName: String, val sha256: String, val label: String)

data class OtaProgress(val percent: Int, val stage: String, val done: Boolean = false, val error: String? = null)

/**
 * What a ring implementation can actually do. Advisory: it tells UI and API clients which members of
 * [RingController] are real and which are no-ops. The Compose UI does not hide controls from it yet
 * (see docs/ADDING_A_RING.md, "What the UI assumes").
 */
enum class RingCapability {
    TOUCH_EVENTS, BATTERY, CLOCK_SYNC, DISPLAY_TEXT, BRIGHTNESS, GESTURE_MESSAGE, MODES, MOUSE,
    MOTION_STREAM, HEART_RATE, FIND_RING, RAW_COMMANDS, FIRMWARE_UPDATE;

    companion object {
        /** Everything: the SY01 with our custom firmware. */
        val ALL: Set<RingCapability> = values().toSet()
    }
}

interface RingController {
    /** Features this implementation really supports. Default: everything (SY01 + custom firmware). */
    val capabilities: Set<RingCapability> get() = RingCapability.ALL

    val connection: StateFlow<Connection>
    val status: StateFlow<RingStatus>
    val events: SharedFlow<RingEvent>
    /** Motion batches; only flowing while [setMotionStreaming] is on or a recording runs. */
    val motion: SharedFlow<MotionBatch>
    /** Bounded (last ~2000 lines) human-readable log for the dev console. */
    val log: StateFlow<List<LogLine>>

    /** Connect and stay connected: after a drop (ring reboot after a firmware update, out of range)
     *  the implementation keeps retrying until [disconnect]. Saved message and mouse settings are
     *  re-sent on every connect. */
    fun connect()
    fun disconnect()

    // --- display
    /** Show text now. ≤5 chars: static; longer: scrolls. */
    fun showText(text: String)
    val gestureMessage: StateFlow<GestureMessage>
    /** Saved on the phone, sent to the ring now and on every connect. */
    fun setGestureMessage(message: GestureMessage)
    /** Play the gesture message now (screen test). */
    fun playGestureMessage()
    fun setGestureDetector(on: Boolean)
    fun setBrightness(level: Int)
    fun setScreenTimeout(seconds: Int)

    // --- modes / clock
    fun setMode(mode: RingMode)
    /** Push the phone's local time to the ring now (also done automatically on connect). */
    fun syncClock()

    // --- mouse
    val mouseSettings: StateFlow<MouseSettings>
    fun setMouseSettings(settings: MouseSettings)

    // --- motion
    fun setMotionStreaming(on: Boolean)

    // --- misc
    fun findRing()

    // --- developer
    /** Send a raw frame body as hex, e.g. "02 04 10" (group key flag payload). Returns an error or null. */
    fun sendRaw(hex: String): String?
    val recording: StateFlow<RecordingState?>
    fun startRecording(label: String): String?
    fun stopRecording()
    fun firmwareImages(): List<FirmwareImage>
    val ota: StateFlow<OtaProgress?>
    /** [confirm] must equal the first 8 hex chars of the image's SHA-256 (explicit user confirmation gate). */
    fun startFirmwareUpdate(image: FirmwareImage, confirm: String): String?
    fun powerOff()
}
