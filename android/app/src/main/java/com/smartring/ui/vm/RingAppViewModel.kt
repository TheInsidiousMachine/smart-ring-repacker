package com.smartring.ui.vm

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smartring.core.ApiSettings
import com.smartring.core.ConnectionState
import com.smartring.core.CustomGesture
import com.smartring.core.FirmwareImage
import com.smartring.core.GestureAction
import com.smartring.core.GestureMessage
import com.smartring.core.GestureStudio
import com.smartring.core.LogLine
import com.smartring.core.MouseSettings
import com.smartring.core.RingController
import com.smartring.core.RingEvent
import com.smartring.core.RingMode
import com.smartring.core.RingServices
import com.smartring.ui.logic.DevCommand
import com.smartring.ui.prefs.SharedUiPreferences
import com.smartring.ui.prefs.UiPreferences
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivityLine(
    val atMs: Long,
    val title: String,
    val detail: String,
    val ok: Boolean?,
)

class RingAppViewModel(
    private val ring: RingController,
    private val studio: GestureStudio,
    private val prefs: UiPreferences,
    private val newToken: () -> String = { TokenGenerator.new() },
) : ViewModel() {
    val connection = ring.connection
    val status = ring.status
    val gestureMessage = ring.gestureMessage
    val mouseSettings = ring.mouseSettings
    val recording = ring.recording
    val ota = ring.ota
    val gestures = studio.gestures
    val listening = studio.listening
    val training = studio.training
    val apiSettings = studio.apiSettings
    val apiStatus = studio.apiStatus

    private val _devMode = MutableStateFlow(prefs.devMode)
    val devMode: StateFlow<Boolean> = _devMode

    private val _saved = MutableStateFlow(prefs.savedMessages().asReversed())
    val savedMessages: StateFlow<List<String>> = _saved

    private val _events = MutableStateFlow<List<RingEvent>>(emptyList())
    val recentEvents: StateFlow<List<RingEvent>> = _events

    private val _liveBpm = MutableStateFlow(ring.status.value.heartBpm)
    val liveBpm: StateFlow<Int?> = _liveBpm

    private val _pulse = MutableStateFlow(0)
    val heartPulse: StateFlow<Int> = _pulse

    private val _activity = MutableStateFlow<List<ActivityLine>>(emptyList())
    val activity: StateFlow<List<ActivityLine>> = _activity

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private val _recordLabel = MutableStateFlow(RECORD_LABELS.first())
    val recordLabel: StateFlow<String> = _recordLabel

    private val _logFilter = MutableStateFlow("")
    val logFilter: StateFlow<String> = _logFilter
    private val _logEpoch = MutableStateFlow(0)
    private var hideThrough: LogLine? = null

    val consoleLines: StateFlow<List<LogLine>> = combine(ring.log, _logFilter, _logEpoch) { log, filter, _ ->
        val cut = hideThrough
        val start = if (cut == null) {
            0
        } else {
            val index = log.indexOfLast { it.atMs == cut.atMs && it.text == cut.text }
            if (index >= 0) index + 1 else 0
        }
        val slice = log.drop(start)
        if (filter.isBlank()) slice else slice.filter { it.text.contains(filter, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            ring.status.collect { state ->
                state.heartBpm?.let { _liveBpm.value = it }
            }
        }
        viewModelScope.launch {
            ring.events.collect { event ->
                _events.update { (listOf(event) + it).take(40) }
                if (event is RingEvent.HeartBeat) {
                    _liveBpm.value = event.bpm
                    _pulse.update { it + 1 }
                }
            }
        }
        viewModelScope.launch {
            studio.hits.collect { hit ->
                val percent = (hit.confidence * 100).toInt()
                pushActivity(ActivityLine(hit.atMs, hit.name, "Recognized · $percent%", null))
            }
        }
        viewModelScope.launch {
            studio.actionResults.collect { result ->
                pushActivity(ActivityLine(result.atMs, result.gestureName, "${result.action} — ${result.detail}", result.ok))
            }
        }
    }

    fun connect() = ring.connect()
    fun disconnect() = ring.disconnect()

    fun setMode(mode: RingMode) = ring.setMode(mode)
    fun setHeart(on: Boolean) = ring.setMode(if (on) RingMode.HEART else RingMode.CLOCK)
    fun syncClock() = ring.syncClock()
    fun setBrightness(level: Int) = ring.setBrightness(level)
    fun setScreenTimeout(seconds: Int) = ring.setScreenTimeout(seconds)
    fun findRing() {
        ring.findRing()
        notice("Find command sent")
    }

    fun showText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        ring.showText(trimmed)
    }

    fun setGestureMessage(message: GestureMessage) {
        ring.setGestureMessage(
            message.copy(
                msPerStep = message.msPerStep.coerceIn(60, 1500),
                plays = message.plays.coerceIn(1, 5),
            ),
        )
        val connected = ring.connection.value.state == ConnectionState.CONNECTED
        notice(if (connected) "Sent to ring" else "Saved. Sends when the ring connects")
    }

    fun playGestureMessage() {
        ring.playGestureMessage()
        notice("Playing on the ring")
    }

    fun setGestureDetector(on: Boolean) = ring.setGestureDetector(on)
    fun setMouseSettings(settings: MouseSettings) = ring.setMouseSettings(settings)

    /**
     * Local adapter. [com.smartring.core.RingController] has no recenter call.
     * Build 14 recaptures the neutral angle on `7A 0B 00` (`RingProtocol.customMouseRecenter`).
     */
    fun recenterMouse() {
        val error = ring.sendRaw("7A 0B 00")
        notice(error ?: "Neutral recaptured")
    }

    fun setDevMode(on: Boolean) {
        prefs.devMode = on
        _devMode.value = on
    }

    fun saveQuickMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        prefs.saveMessage(trimmed)
        _saved.value = prefs.savedMessages().asReversed()
    }

    fun removeQuickMessage(text: String) {
        prefs.removeMessage(text)
        _saved.value = prefs.savedMessages().asReversed()
    }

    fun setRecordLabel(label: String) {
        if (label in RECORD_LABELS) _recordLabel.value = label
    }

    fun toggleRecording() {
        if (ring.recording.value != null) {
            ring.stopRecording()
            return
        }
        val error = ring.startRecording(_recordLabel.value)
        if (error != null) notice(error)
    }

    fun firmwareImages(): List<FirmwareImage> = ring.firmwareImages()

    fun startFirmwareUpdate(image: FirmwareImage, confirm: String): String? {
        if (ring.connection.value.state != ConnectionState.CONNECTED) {
            notice("Connect the ring before a firmware update")
            return "Not connected"
        }
        val typed = confirm.trim().lowercase()
        val expected = image.sha256.take(8).lowercase()
        if (typed != expected) {
            notice("Type the first 8 characters of the hash to confirm")
            return "Type the first 8 characters of the hash to confirm"
        }
        val error = ring.startFirmwareUpdate(image, typed)
        if (error != null) notice(error) else notice("Firmware update started")
        return error
    }

    fun powerOff(): String? {
        if (ring.connection.value.state != ConnectionState.CONNECTED) {
            notice("Connect the ring before powering it off")
            return "Not connected"
        }
        ring.powerOff()
        notice("Power off sent. Put the ring on its charger to turn it on.")
        return null
    }

    fun setLogFilter(filter: String) {
        _logFilter.value = filter
    }

    fun clearConsole() {
        hideThrough = ring.log.value.lastOrNull()
        _logEpoch.value++
    }

    fun sendRaw(hex: String): String? {
        val error = ring.sendRaw(hex)
        if (error != null) notice(error)
        return error
    }

    fun runPreset(command: DevCommand) {
        var firstError: String? = null
        for (body in command.bodies) {
            val error = ring.sendRaw(body)
            if (error != null && firstError == null) firstError = error
        }
        notice(firstError ?: command.label)
    }

    fun setListening(on: Boolean) = studio.setListening(on)

    fun createGesture(name: String): CustomGesture? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return studio.createGesture(trimmed)
    }

    fun renameGesture(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        studio.renameGesture(id, trimmed)
    }

    fun deleteGesture(id: String) {
        if (studio.training.value?.gestureId == id) studio.stopTraining()
        studio.deleteGesture(id)
    }

    fun setEnabled(id: String, enabled: Boolean) = studio.setEnabled(id, enabled)
    fun setStrictness(id: String, strictness: Int) = studio.setStrictness(id, strictness)
    fun setAction(id: String, action: GestureAction) = studio.setAction(id, action)
    fun testAction(id: String) = studio.testAction(id)
    fun startTraining(id: String) = studio.startTraining(id, 5)
    fun stopTraining() = studio.stopTraining()
    fun clearExamples(id: String) = studio.clearExamples(id)

    fun updateApi(settings: ApiSettings): String? {
        if (settings.port !in 1..65535) return "Port must be 1–65535"
        var next = settings
        if (next.enabled && next.token.isBlank()) next = next.copy(token = newToken())
        studio.setApiSettings(next)
        return null
    }

    fun regenerateToken() {
        val current = studio.apiSettings.value
        studio.setApiSettings(current.copy(token = newToken()))
    }

    private fun pushActivity(line: ActivityLine) {
        _activity.update { (listOf(line) + it).take(30) }
    }

    private fun notice(text: String) {
        _notices.tryEmit(text)
    }

    companion object {
        val RECORD_LABELS = listOf("target", "bumps_only", "flick_only", "everyday", "shake")

        fun factory(
            app: Application,
            ring: RingController = RingServices.ring,
            studio: GestureStudio = RingServices.studio,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RingAppViewModel(ring, studio, SharedUiPreferences(app)) as T
        }
    }
}

object TokenGenerator {
    fun new(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
