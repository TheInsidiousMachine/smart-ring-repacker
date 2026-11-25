package com.smartring.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory GestureStudio for UI previews and UI tests. No recognition, no network. */
class FakeGestureStudio(private val clock: () -> Long = System::currentTimeMillis) : GestureStudio {
    private val _gestures = MutableStateFlow(listOf(
        CustomGesture("g1", "Swipe right", 5, GestureAction.PcKeys("alt+tab")),
        CustomGesture("g2", "Double knock", 4, GestureAction.Webhook("http://ai-server.local/ring")),
        CustomGesture("g3", "Circle", 1),
    ))
    override val gestures: StateFlow<List<CustomGesture>> = _gestures
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening
    override fun setListening(on: Boolean) { _listening.value = on }
    private val _hits = MutableSharedFlow<GestureHit>(extraBufferCapacity = 16)
    override val hits: SharedFlow<GestureHit> = _hits
    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 16)
    override val actionResults: SharedFlow<ActionResult> = _results
    private var next = 4

    private fun edit(id: String, f: (CustomGesture) -> CustomGesture) = _gestures.update { l -> l.map { if (it.id == id) f(it) else it } }
    override fun createGesture(name: String) = CustomGesture("g${next++}", name, 0).also { g -> _gestures.update { it + g } }
    override fun renameGesture(id: String, name: String) = edit(id) { it.copy(name = name) }
    override fun deleteGesture(id: String) = _gestures.update { l -> l.filter { it.id != id } }
    override fun setAction(id: String, action: GestureAction) = edit(id) { it.copy(action = action) }
    override fun setEnabled(id: String, enabled: Boolean) = edit(id) { it.copy(enabled = enabled) }
    override fun setStrictness(id: String, strictness: Int) = edit(id) { it.copy(strictness = strictness.coerceIn(0, 100)) }

    private val _training = MutableStateFlow<TrainingState?>(null)
    override val training: StateFlow<TrainingState?> = _training
    override fun startTraining(id: String, examplesWanted: Int) {
        val have = _gestures.value.firstOrNull { it.id == id }?.examples ?: 0
        _training.value = TrainingState(id, have, examplesWanted, "Do the gesture now", true)
    }
    override fun stopTraining() { _training.value = null }
    /** Test hook: pretend one example was captured. */
    fun fakeExample() {
        val t = _training.value ?: return
        edit(t.gestureId) { it.copy(examples = it.examples + 1) }
        val n = t.examplesRecorded + 1
        _training.value = if (n >= t.examplesWanted) null else t.copy(examplesRecorded = n, prompt = "Got it ($n/${t.examplesWanted})")
    }
    override fun clearExamples(id: String) = edit(id) { it.copy(examples = 0) }
    override fun testAction(id: String) {
        val g = _gestures.value.firstOrNull { it.id == id } ?: return
        _hits.tryEmit(GestureHit(g.id, g.name, 0.93, clock()))
        _results.tryEmit(ActionResult(g.name, g.action.toString(), true, "fake", clock()))
    }
    private val _api = MutableStateFlow(ApiSettings())
    override val apiSettings: StateFlow<ApiSettings> = _api
    private val _apiStatus = MutableStateFlow(ApiStatus(false, emptyList(), 0))
    override val apiStatus: StateFlow<ApiStatus> = _apiStatus
    override fun setApiSettings(settings: ApiSettings) {
        _api.value = settings
        _apiStatus.value = ApiStatus(settings.enabled, if (settings.enabled) listOf("http://192.0.2.10:${settings.port}") else emptyList(), 0)
    }
}
