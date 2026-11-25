package com.smartring.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Simulated ring for UI previews, screenshots and tests. No Bluetooth.
 * While motion streaming is on it emits 10-sample batches every 100 ms (gravity on z plus a
 * little noise); tests can inject exact samples with [emitMotion].
 */
class FakeRingController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) : RingController {
    private val _connection = MutableStateFlow(Connection())
    override val connection: StateFlow<Connection> = _connection
    private val _status = MutableStateFlow(RingStatus())
    override val status: StateFlow<RingStatus> = _status
    private val _events = MutableSharedFlow<RingEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RingEvent> = _events
    private val _motion = MutableSharedFlow<MotionBatch>(extraBufferCapacity = 64)
    override val motion: SharedFlow<MotionBatch> = _motion
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

    /** Every command the fake received, for tests ("showText HELLO", "setMode MOUSE", …). */
    val commands = mutableListOf<String>()
    var screen: String = ""; private set

    private var streamJob: Job? = null
    private var seq = 0

    private fun cmd(s: String) { synchronized(commands) { commands += s }; log("-> $s") }
    fun log(text: String) = _log.update { (it + LogLine(clock(), text)).takeLast(2000) }

    override fun connect() {
        cmd("connect")
        _connection.value = Connection(ConnectionState.CONNECTED, "SY01 (fake)", "Connected: SY01 (fake)")
        _status.value = RingStatus(batteryPercent = 76, charging = false, firmwareVersion = "2.1.4",
            customBuild = "build 12 (fake)", mode = RingMode.CLOCK, clockSyncedAtMs = clock(),
            gestureDetectorOn = true, screenBrightness = 2, screenTimeoutSec = 5)
    }
    override fun disconnect() { cmd("disconnect"); _connection.value = Connection(); setMotionStreaming(false) }

    override fun showText(text: String) { cmd("showText $text"); screen = text.uppercase().take(5) }
    override fun setGestureMessage(message: GestureMessage) { cmd("setGestureMessage $message"); _message.value = message }
    override fun playGestureMessage() {
        cmd("playGestureMessage")
        screen = _message.value.text.ifBlank { "HI THERE" }.uppercase().take(5)
        _events.tryEmit(RingEvent.GestureFired(clock()))
    }
    override fun setGestureDetector(on: Boolean) { cmd("setGestureDetector $on"); _status.update { it.copy(gestureDetectorOn = on) } }
    override fun setBrightness(level: Int) { cmd("setBrightness $level"); _status.update { it.copy(screenBrightness = level.coerceIn(0, 3)) } }
    override fun setScreenTimeout(seconds: Int) { cmd("setScreenTimeout $seconds"); _status.update { it.copy(screenTimeoutSec = seconds) } }
    override fun setMode(mode: RingMode) {
        cmd("setMode $mode"); _status.update { it.copy(mode = mode) }
        _events.tryEmit(RingEvent.ModeChanged(mode, clock()))
    }
    override fun syncClock() { cmd("syncClock"); _status.update { it.copy(clockSyncedAtMs = clock()) } }
    override fun setMouseSettings(settings: MouseSettings) { cmd("setMouseSettings $settings"); _mouse.value = settings }

    override fun setMotionStreaming(on: Boolean) {
        cmd("setMotionStreaming $on")
        streamJob?.cancel(); streamJob = null
        if (!on) return
        streamJob = scope.launch {
            var t = 0
            while (isActive) {
                val batch = List(10) { k ->
                    val ph = (t + k) / 100.0
                    intArrayOf((40 * sin(2 * PI * 0.7 * ph)).roundToInt(), (30 * sin(2 * PI * 0.3 * ph)).roundToInt(), 2048)
                }
                t += 10
                emitMotion(batch)
                delay(100)
            }
        }
    }

    /** Inject exact samples (tests / simulated gestures). */
    fun emitMotion(samples: List<IntArray>) {
        _motion.tryEmit(MotionBatch(seq++ and 0xff, samples, clock()))
        _recording.value?.let { r -> _recording.value = r.copy(samples = r.samples + samples.size) }
    }
    /** Inject a ring event (tests). */
    fun emitEvent(e: RingEvent) { _events.tryEmit(e) }

    override fun findRing() = cmd("findRing")
    override fun sendRaw(hex: String): String? {
        val ok = hex.trim().split(Regex("\\s+")).all { it.length == 2 && it.toIntOrNull(16) != null }
        if (!ok) return "Not hex bytes: \"$hex\""
        cmd("raw $hex"); return null
    }
    override fun startRecording(label: String): String? {
        if (_recording.value != null) return "Already recording"
        cmd("startRecording $label"); _recording.value = RecordingState(label, 0, 99.0, 0, "recordings/${label}_fake.csv")
        setMotionStreaming(true); return null
    }
    override fun stopRecording() { cmd("stopRecording"); _recording.value = null; setMotionStreaming(false) }
    override fun firmwareImages() = listOf(
        FirmwareImage("SY01_30330000_V2.1.4_stock.bin", "8fb5ee3759dea842305cb23b9be2fd269db91d1a9ff04e3cd1578661fa531e50", "stock V2.1.4"),
        FirmwareImage("SY01_30330000_V2.1.4_custom_b046ee55.bin", "b046ee55944839b748e8a8fe0b76502be6dabdd5da4d1e712b89c17aaabf23a1", "custom build 11"),
    )
    override fun startFirmwareUpdate(image: FirmwareImage, confirm: String): String? {
        if (!image.sha256.startsWith(confirm.lowercase()) || confirm.length != 8) return "Type the first 8 characters of the hash to confirm"
        cmd("firmwareUpdate ${image.fileName}")
        scope.launch {
            for (p in 0..100 step 5) { _ota.value = OtaProgress(p, if (p < 100) "Sending" else "Done", done = p == 100); delay(80) }
        }
        return null
    }
    override fun powerOff() = cmd("powerOff")
}
