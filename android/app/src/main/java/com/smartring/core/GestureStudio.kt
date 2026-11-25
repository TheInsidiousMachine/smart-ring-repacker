package com.smartring.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/*
 * Phone-side trainable gestures, the actions they trigger, and the local API other software
 * uses to talk to the ring. Implemented in com.smartring.gestures / com.smartring.api;
 * consumed by com.smartring.ui. Treat changes as API changes.
 */

@Serializable
data class CustomGesture(
    val id: String,
    val name: String,
    /** Number of recorded training examples (recognition is enabled at >= 3). */
    val examples: Int,
    val action: GestureAction = GestureAction.None,
    val enabled: Boolean = true,
    /** 0..100, higher = stricter match (fewer false fires). */
    val strictness: Int = 60,
)

@Serializable
sealed interface GestureAction {
    @Serializable data object None : GestureAction
    /** HTTP request. [body] may use {gesture}, {confidence}, {time} placeholders. */
    @Serializable data class Webhook(val url: String, val method: String = "POST",
                                     val body: String = "{\"gesture\":\"{gesture}\",\"confidence\":{confidence},\"time\":\"{time}\"}",
                                     val headers: Map<String, String> = emptyMap()) : GestureAction
    /** Show text on the ring screen. */
    @Serializable data class RingText(val text: String) : GestureAction
    /** Key combo or text sent to the PC through the companion daemon (pc/ring_bridge.py), e.g. "ctrl+c". */
    @Serializable data class PcKeys(val keys: String) : GestureAction
    /** Android media key: "play_pause", "next", "previous", "volume_up", "volume_down". */
    @Serializable data class MediaKey(val key: String) : GestureAction
    /** Only publish on the API event stream (for external software to react). */
    @Serializable data object ApiEventOnly : GestureAction
}

/** A recognized gesture (also published on the API event stream). */
data class GestureHit(val gestureId: String, val name: String, val confidence: Double, val atMs: Long)

/** Training session progress for one gesture. */
data class TrainingState(
    val gestureId: String,
    val examplesRecorded: Int,
    val examplesWanted: Int,
    /** "Do the gesture now", "Hold still…", "Got it (3/5)" … */
    val prompt: String,
    val listening: Boolean,
)

@Serializable
data class ApiSettings(
    val enabled: Boolean = false,
    val port: Int = 8765,
    /** Bearer token required by every API call; generated on first enable. */
    val token: String = "",
    /** Bind to all interfaces (reachable from the LAN / VPN) instead of localhost only. */
    val lan: Boolean = true,
)

data class ApiStatus(val running: Boolean, val urls: List<String>, val clients: Int, val error: String? = null)

/** Result of firing an action, for the UI's activity list. */
data class ActionResult(val gestureName: String, val action: String, val ok: Boolean, val detail: String, val atMs: Long)

interface GestureStudio {
    val gestures: StateFlow<List<CustomGesture>>
    /** Live recognition on/off (turns ring motion streaming on/off through RingController). */
    val listening: StateFlow<Boolean>
    fun setListening(on: Boolean)
    val hits: SharedFlow<GestureHit>
    val actionResults: SharedFlow<ActionResult>

    fun createGesture(name: String): CustomGesture
    fun renameGesture(id: String, name: String)
    fun deleteGesture(id: String)
    fun setAction(id: String, action: GestureAction)
    fun setEnabled(id: String, enabled: Boolean)
    fun setStrictness(id: String, strictness: Int)

    /** Start recording examples for a gesture (motion streaming on); each detected motion burst = one example. */
    fun startTraining(id: String, examplesWanted: Int = 5)
    fun stopTraining()
    /** Drop all examples of a gesture. */
    fun clearExamples(id: String)
    val training: StateFlow<TrainingState?>

    /** Run a gesture's action once (test button). */
    fun testAction(id: String)

    val apiSettings: StateFlow<ApiSettings>
    fun setApiSettings(settings: ApiSettings)
    val apiStatus: StateFlow<ApiStatus>
}
