package com.smartring.testbed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.smartring.core.Connection
import com.smartring.core.ConnectionState
import com.smartring.core.FirmwareImage
import com.smartring.core.GestureMessage
import com.smartring.core.LogLine
import com.smartring.core.MotionBatch
import com.smartring.core.MouseSettings
import com.smartring.core.MouseStyle
import com.smartring.core.OtaProgress
import com.smartring.core.RecordingState
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
import java.io.File

/**
 * The real [RingController]: an adapter over [RingLink] (BLE + protocol), used by the Compose UI
 * and the gesture/API module. All RingLink callbacks arrive on the main thread.
 */
class RealRingController(private val context: Context) : RingController, RingLink.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ring_controller", Context.MODE_PRIVATE)

    private val _connection = MutableStateFlow(Connection())
    override val connection: StateFlow<Connection> = _connection
    private val _status = MutableStateFlow(RingStatus())
    override val status: StateFlow<RingStatus> = _status
    private val _events = MutableSharedFlow<RingEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<RingEvent> = _events
    private val _motion = MutableSharedFlow<MotionBatch>(extraBufferCapacity = 256)
    override val motion: SharedFlow<MotionBatch> = _motion
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    override val log: StateFlow<List<LogLine>> = _log
    private val _message = MutableStateFlow(loadMessage())
    override val gestureMessage: StateFlow<GestureMessage> = _message
    private val _mouse = MutableStateFlow(loadMouse())
    override val mouseSettings: StateFlow<MouseSettings> = _mouse
    private val _recording = MutableStateFlow<RecordingState?>(null)
    override val recording: StateFlow<RecordingState?> = _recording
    private val _ota = MutableStateFlow<OtaProgress?>(null)
    override val ota: StateFlow<OtaProgress?> = _ota

    private var streaming = false
    private var lastHeartBpm = 0

    init {
        RingLink.init(context)
        RingLink.addListener(this)
        val f = IntentFilter().apply { addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED) }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (RingLink.state == RingLink.State.CONNECTED) syncClock() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(rx, f)
        main.postDelayed(object : Runnable {            // keep the ring clock honest
            override fun run() { if (RingLink.state == RingLink.State.CONNECTED) syncClock(); main.postDelayed(this, 6 * 3600_000L) }
        }, 6 * 3600_000L)
    }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------ RingLink.Listener
    override fun onState(state: RingLink.State, detail: String) {
        val s = when (state) {
            RingLink.State.DISCONNECTED -> ConnectionState.DISCONNECTED
            RingLink.State.CONNECTING -> ConnectionState.CONNECTING
            RingLink.State.CONNECTED -> ConnectionState.CONNECTED
        }
        val name = if (s == ConnectionState.CONNECTED) detail.removePrefix("Connected: ") else null
        val wasConnected = _connection.value.state == ConnectionState.CONNECTED
        _connection.value = Connection(s, name, detail)
        if (s == ConnectionState.CONNECTED && !wasConnected) onConnected()
        if (s == ConnectionState.DISCONNECTED) streaming = false
    }

    private fun onConnected() {
        // RingLink already re-sends the gesture message and syncs the clock on connect.
        _status.update { it.copy(clockSyncedAtMs = now()) }
        sendMouse(_mouse.value)
        RingLink.send(RingProtocol.queryScreenTimeout(), "read screen timeout")
        RingLink.send(RingProtocol.customGesture(_status.value.gestureDetectorOn ?: true), "gesture detector state")
    }

    override fun onLog(line: String) = _log.update { (it + LogLine(now(), line)).takeLast(2000) }

    override fun onEvent(event: RingProtocol.Event) {
        when (event) {
            is RingProtocol.Event.Motion -> _motion.tryEmit(MotionBatch(event.seq, event.samples, now()))
            is RingProtocol.Event.Ppg -> if (event.bpm > 0 && event.bpm != lastHeartBpm) {
                lastHeartBpm = event.bpm
                _status.update { it.copy(heartBpm = event.bpm) }
                _events.tryEmit(RingEvent.HeartBeat(event.bpm, now()))
            }
            is RingProtocol.Event.Setting -> onSetting(event.text)
            else -> {}
        }
    }

    private fun onSetting(t: String) {
        val ts = now()
        Regex("^Battery (\\d+)%").find(t)?.let { m -> _status.update { it.copy(batteryPercent = m.groupValues[1].toInt()) } }
        Regex("^Firmware v([\\d.]+)").find(t)?.let { m -> _status.update { it.copy(firmwareVersion = m.groupValues[1]) } }
        Regex("^LED open=(\\w+) level=(\\d+)").find(t)?.let { m ->
            _status.update { it.copy(screenBrightness = if (m.groupValues[1] == "true") m.groupValues[2].toInt() else 0) }
        }
        Regex("^Screen-on time (\\d+)s").find(t)?.let { m -> _status.update { it.copy(screenTimeoutSec = m.groupValues[1].toInt()) } }
        Regex("gesture=(on|off) fired=(\\d+) armed=(\\d+)").find(t)?.let { m ->
            _status.update { it.copy(gestureDetectorOn = m.groupValues[1] == "on", gestureFired = m.groupValues[2].toInt(),
                gestureArmed = m.groupValues[3].toInt(), customBuild = it.customBuild ?: "custom") }
        }
        Regex(" build=(\\d+)").find(t)?.let { m -> _status.update { it.copy(customBuild = "build ${m.groupValues[1]}") } }
        Regex(" mode=(clock|mouse|heart|keys)").find(t)?.let { m -> setModeState(modeOf(m.groupValues[1]), emit = false) }
        Regex("^Ring mode (clock|mouse|heart|keys)").find(t)?.let { m -> setModeState(modeOf(m.groupValues[1]), emit = true) }
        Regex("^Ring touch (\\w+)(?: t=(\\d+))?").find(t)?.let { m ->
            val k = when (m.groupValues[1]) {
                "tap" -> TouchKind.TAP; "press" -> TouchKind.HOLD_START; "release" -> TouchKind.HOLD_END
                "right_click" -> TouchKind.RIGHT_CLICK; "five_taps" -> TouchKind.FIVE_TAPS; "long_hold" -> TouchKind.LONG_HOLD
                else -> null
            }
            if (k != null) _events.tryEmit(RingEvent.Touch(k, ts, m.groupValues[2].toIntOrNull()))
        }
        Regex("^Gesture (FIRED|armed) fired=(\\d+) armed=(\\d+)").find(t)?.let { m ->
            _status.update { it.copy(gestureFired = m.groupValues[2].toInt(), gestureArmed = m.groupValues[3].toInt()) }
            _events.tryEmit(if (m.groupValues[1] == "FIRED") RingEvent.GestureFired(ts) else RingEvent.GestureArmed(ts))
        }
        _events.tryEmit(RingEvent.Info(t, ts))
    }

    private fun modeOf(s: String) = when (s) { "clock" -> RingMode.CLOCK; "mouse" -> RingMode.MOUSE; "heart" -> RingMode.HEART; "keys" -> RingMode.KEYS; else -> RingMode.UNKNOWN }
    private fun setModeState(m: RingMode, emit: Boolean) {
        val old = _status.value.mode
        _status.update { it.copy(mode = m, heartBpm = if (m == RingMode.HEART) it.heartBpm else null) }
        if (emit || old != m) _events.tryEmit(RingEvent.ModeChanged(m, now()))
    }

    // ------------------------------------------------------------ commands
    override fun connect() = RingLink.connect()
    override fun disconnect() = RingLink.disconnect()

    override fun showText(text: String) = RingLink.pushText(text)

    override fun setGestureMessage(message: GestureMessage) {
        _message.value = message
        RingLink.setGestureMessage(RingLink.GestureMessage(message.text, message.scroll, message.msPerStep, message.plays))
    }
    private fun loadMessage(): GestureMessage =
        RingLink.savedMessageFrom(context)?.let { GestureMessage(it.text, it.scroll, it.msPerFrame, it.reps) } ?: GestureMessage()
    override fun playGestureMessage() = RingLink.send(RingProtocol.customGestureTest(), "gesture message test")
    override fun setGestureDetector(on: Boolean) {
        RingLink.send(RingProtocol.customGesture(on), if (on) "gesture detector on" else "gesture detector off")
        _status.update { it.copy(gestureDetectorOn = on) }
    }
    override fun setBrightness(level: Int) {
        val l = level.coerceIn(0, 3)
        RingLink.send(RingProtocol.setLedLevel(l > 0, l), "brightness $l")
        _status.update { it.copy(screenBrightness = l) }
    }
    override fun setScreenTimeout(seconds: Int) {
        RingLink.send(RingProtocol.setScreenTimeout(seconds.coerceIn(2, 60)), "screen timeout ${seconds}s")
        _status.update { it.copy(screenTimeoutSec = seconds) }
    }

    override fun setMode(mode: RingMode) {
        val m = when (mode) { RingMode.MOUSE -> 1; RingMode.HEART -> 2; RingMode.KEYS -> 3; else -> 0 }
        RingLink.send(RingProtocol.customMode(m), "mode ${mode.name.lowercase()}")
    }
    override fun syncClock() {
        RingLink.send(RingProtocol.setTime(), "sync clock")
        _status.update { it.copy(clockSyncedAtMs = now()) }
    }

    override fun setMouseSettings(settings: MouseSettings) {
        _mouse.value = settings
        prefs.edit().putInt("m_speed", settings.speed).putInt("m_curve", settings.curve).putInt("m_dz", settings.deadzoneDeg)
            .putBoolean("m_invx", settings.invertX).putBoolean("m_invy", settings.invertY)
            .putBoolean("m_swap", settings.swapAxes).putString("m_style", settings.style.name)
            .putInt("m_rc", settings.rightClickHoldMs).apply()
        sendMouse(settings)
    }
    private fun loadMouse() = MouseSettings(
        style = runCatching { MouseStyle.valueOf(prefs.getString("m_style", null) ?: "POINTER") }.getOrDefault(MouseStyle.POINTER),
        speed = prefs.getInt("m_speed", 50), curve = prefs.getInt("m_curve", 50), deadzoneDeg = prefs.getInt("m_dz", 4),
        invertX = prefs.getBoolean("m_invx", false), invertY = prefs.getBoolean("m_invy", false),
        swapAxes = prefs.getBoolean("m_swap", false), rightClickHoldMs = prefs.getInt("m_rc", 700))
    private fun sendMouse(s: MouseSettings) {
        RingLink.send(RingProtocol.customMouse(s.speed, s.curve, s.deadzoneDeg, mouseFlags(s), s.rightClickHoldMs), "mouse settings")
    }

    override fun setMotionStreaming(on: Boolean) {
        if (on == streaming) return
        streaming = on
        if (_recording.value == null) RingLink.send(RingProtocol.customRecord(on, 6), if (on) "motion stream on" else "motion stream off")
    }

    override fun findRing() = RingLink.send(RingProtocol.findDevice(), "find ring")

    override fun sendRaw(hex: String): String? {
        val parts = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val bytes = parts.map { it.removePrefix("0x").toIntOrNull(16) ?: return "Not hex: \"$it\"" }
        if (bytes.size < 3 || bytes.any { it !in 0..255 }) return "Need at least: group key flag (hex bytes)"
        RingLink.send(RingProtocol.buildRequest(bytes[0], bytes[1], bytes[2], ByteArray(bytes.size - 3) { bytes[it + 3].toByte() }), "raw $hex")
        return null
    }

    private val recTicker = object : Runnable {
        override fun run() {
            val l = RingLink.recLabel
            if (l == null) { _recording.value = null; return }
            _recording.value = RecordingState(l, RingLink.recSamples, RingLink.recRate, RingLink.recGaps, RingLink.recFile ?: "")
            main.postDelayed(this, 500)
        }
    }
    override fun startRecording(label: String): String? {
        val err = RingLink.startRecording(label, fast = true)
        if (err == null) main.post(recTicker)
        return err
    }
    override fun stopRecording() {
        RingLink.stopRecording(); _recording.value = null
        if (streaming) RingLink.send(RingProtocol.customRecord(true, 6), "motion stream on")
    }

    private fun firmwareDir() = File(context.getExternalFilesDir(null), "firmware")
    override fun firmwareImages(): List<FirmwareImage> =
        (firmwareDir().listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".bin") }.mapNotNull { f ->
            val sha = PxiOtaSession.sha256(f.readBytes())
            PxiOtaSession.ALLOWED_SHA256[sha]?.let { FirmwareImage(f.name, sha, it) }
        }.sortedByDescending { it.fileName }

    private val otaTicker = object : Runnable {
        override fun run() {
            val s = RingLink.ota ?: return
            val done = s.state == PxiOtaSession.State.DONE
            val failed = s.state == PxiOtaSession.State.FAILED
            _ota.value = OtaProgress((s.progress * 100).toInt(), s.state.name.lowercase().replace('_', ' '), done || failed, s.failure)
            if (!done && !failed) main.postDelayed(this, 300)
        }
    }
    override fun startFirmwareUpdate(image: FirmwareImage, confirm: String): String? {
        if (confirm.length != 8 || !image.sha256.startsWith(confirm.lowercase())) return "Type the first 8 characters of the hash to confirm"
        val bytes = File(firmwareDir(), image.fileName).takeIf { it.isFile }?.readBytes() ?: return "Image file missing"
        val err = RingLink.startFirmwareUpdate(bytes)
        if (err == null) main.post(otaTicker)
        return err
    }

    override fun powerOff() = RingLink.send(RingProtocol.powerOff(), "power off")
}

/** 7A 0A flags byte: 1 invert x, 2 invert y, 4 swap axes, 8 joystick style (build 14+). */
internal fun mouseFlags(s: MouseSettings): Int =
    (if (s.invertX) 1 else 0) or (if (s.invertY) 2 else 0) or (if (s.swapAxes) 4 else 0) or
        (if (s.style == MouseStyle.JOYSTICK) 8 else 0)
