package com.smartring.gestures

import com.smartring.api.RingApiServer
import com.smartring.core.ActionResult
import com.smartring.core.ApiSettings
import com.smartring.core.ApiStatus
import com.smartring.core.CustomGesture
import com.smartring.core.GestureAction
import com.smartring.core.GestureHit
import com.smartring.core.GestureStudio
import com.smartring.core.MotionBatch
import com.smartring.core.RingController
import com.smartring.core.TrainingState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RealGestureStudio(
    private val ring: RingController,
    private val storeDir: File,
    private val scope: CoroutineScope,
    private val platform: PlatformActions,
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GestureStudio {

    private val storeFile = File(storeDir, "gestures.json")
    private val actionExecutor = ActionExecutor(ring, platform, client, clock, ioDispatcher)
    private val segmenter = MotionSegmenter()

    private val _gestures = MutableStateFlow<List<CustomGesture>>(emptyList())
    override val gestures: StateFlow<List<CustomGesture>> = _gestures.asStateFlow()

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _hits = MutableSharedFlow<GestureHit>(replay = 1, extraBufferCapacity = 64)
    override val hits: SharedFlow<GestureHit> = _hits.asSharedFlow()

    private val _actionResults = MutableSharedFlow<ActionResult>(replay = 1, extraBufferCapacity = 64)
    override val actionResults: SharedFlow<ActionResult> = _actionResults.asSharedFlow()

    private val _training = MutableStateFlow<TrainingState?>(null)
    override val training: StateFlow<TrainingState?> = _training.asStateFlow()

    private val _apiSettings = MutableStateFlow(ApiSettings())
    override val apiSettings: StateFlow<ApiSettings> = _apiSettings.asStateFlow()

    private val _apiStatus = MutableStateFlow(ApiStatus(false, emptyList(), 0))
    override val apiStatus: StateFlow<ApiStatus> = _apiStatus.asStateFlow()

    private val trainedExamples = ConcurrentHashMap<String, MutableList<TrainedExample>>()
    private val gestureModels = ConcurrentHashMap<String, TrainedGestureModel>()

    private var nextIdCounter = AtomicInteger(1)
    private var apiServer: RingApiServer? = null
    private var motionJob: Job? = null
    private var clientCountJob: Job? = null

    init {
        loadPersisted()
        startMotionCollector()
    }

    private fun loadPersisted() {
        val entries = GesturePersistence.load(storeFile)
        if (entries.isNotEmpty()) {
            val list = mutableListOf<CustomGesture>()
            var maxNum = 0
            for (entry in entries) {
                list += entry.gesture
                val num = entry.gesture.id.removePrefix("g").toIntOrNull()
                if (num != null && num > maxNum) maxNum = num

                val examplesList = entry.examples.map { exData ->
                    val raw = exData.raw.map { it.toIntArray() }
                    val features = FeatureExtractor.extractAll(raw)
                    val resampled = FeatureExtractor.resample(features, 32)
                    TrainedExample(raw, exData.length, exData.peakJerk, resampled)
                }.toMutableList()
                trainedExamples[entry.gesture.id] = examplesList
            }
            nextIdCounter.set(maxNum + 1)
            _gestures.value = list
            rebuildModels()
        }
    }

    private fun savePersisted() {
        val list = _gestures.value
        val entries = list.map { g ->
            val examples = trainedExamples[g.id]?.map { ex ->
                StoredExampleData(
                    raw = ex.rawSamples.map { it.toList() },
                    length = ex.length,
                    peakJerk = ex.peakJerk,
                )
            } ?: emptyList()
            StoredGestureEntry(g, examples)
        }
        GesturePersistence.save(storeFile, entries)
    }

    private fun rebuildModels() {
        gestureModels.clear()
        for (g in _gestures.value) {
            val examples = trainedExamples[g.id] ?: emptyList()
            if (examples.size >= 3) {
                gestureModels[g.id] = TrainedGestureModel(g.id, g.name, g.strictness, examples)
            }
        }
    }

    private fun startMotionCollector() {
        motionJob?.cancel()
        motionJob = scope.launch {
            ring.motion.collect { batch ->
                handleMotionBatch(batch)
            }
        }
    }

    private fun updateMotionStreaming() {
        val needed = _listening.value || _training.value != null
        ring.setMotionStreaming(needed)
    }

    private fun handleMotionBatch(batch: MotionBatch) {
        val isTraining = _training.value != null
        val isListening = _listening.value
        if (!isTraining && !isListening) return

        var sampleTime = batch.receivedAtMs
        for (sample in batch.samples) {
            val burst = segmenter.feedSample(sample, sampleTime)
            sampleTime += 10L // approximate ~100Hz delta

            if (burst != null) {
                handleCompletedBurst(burst)
            }
        }
    }

    private fun handleCompletedBurst(burst: CandidateBurst) {
        val currentTraining = _training.value
        if (currentTraining != null) {
            // Training mode: record this burst as an example
            val gId = currentTraining.gestureId
            val resampled = FeatureExtractor.resample(burst.features, 32)
            val example = TrainedExample(burst.rawSamples, burst.length, burst.peakJerk, resampled)

            val examplesList = trainedExamples.getOrPut(gId) { mutableListOf() }
            examplesList.add(example)
            val count = examplesList.size

            _gestures.update { list ->
                list.map { if (it.id == gId) it.copy(examples = count) else it }
            }
            rebuildModels()
            savePersisted()

            if (count >= currentTraining.examplesWanted) {
                _training.value = null
                updateMotionStreaming()
            } else {
                _training.value = currentTraining.copy(
                    examplesRecorded = count,
                    prompt = "Got it ($count/${currentTraining.examplesWanted}) - hold still...",
                )
            }
            return
        }

        if (_listening.value) {
            // Recognition mode
            var bestMatch: GestureMatch? = null
            var bestDist = Float.MAX_VALUE

            val gesturesMap = _gestures.value.associateBy { it.id }
            for ((id, model) in gestureModels) {
                val gesture = gesturesMap[id] ?: continue
                if (!gesture.enabled || !model.isTrained) continue

                val match = model.match(burst)
                if (match != null && match.distance < bestDist) {
                    bestDist = match.distance
                    bestMatch = match
                }
            }

            if (bestMatch != null) {
                val hit = GestureHit(bestMatch.gestureId, bestMatch.name, bestMatch.confidence, burst.endMs)
                _hits.tryEmit(hit)

                val gesture = gesturesMap[bestMatch.gestureId]
                if (gesture != null) {
                    scope.launch(ioDispatcher) {
                        val result = actionExecutor.execute(gesture.action, hit)
                        _actionResults.tryEmit(result)
                    }
                }
            }
        }
    }

    override fun setListening(on: Boolean) {
        _listening.value = on
        if (on) {
            segmenter.reset()
        }
        updateMotionStreaming()
    }

    override fun createGesture(name: String): CustomGesture {
        val id = "g${nextIdCounter.getAndIncrement()}"
        val gesture = CustomGesture(id = id, name = name, examples = 0)
        _gestures.update { it + gesture }
        savePersisted()
        return gesture
    }

    override fun renameGesture(id: String, name: String) {
        _gestures.update { list ->
            list.map { if (it.id == id) it.copy(name = name) else it }
        }
        rebuildModels()
        savePersisted()
    }

    override fun deleteGesture(id: String) {
        if (_training.value?.gestureId == id) {
            stopTraining()
        }
        _gestures.update { list -> list.filter { it.id != id } }
        trainedExamples.remove(id)
        gestureModels.remove(id)
        savePersisted()
    }

    override fun setAction(id: String, action: GestureAction) {
        _gestures.update { list ->
            list.map { if (it.id == id) it.copy(action = action) else it }
        }
        savePersisted()
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        _gestures.update { list ->
            list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        savePersisted()
    }

    override fun setStrictness(id: String, strictness: Int) {
        val coerced = strictness.coerceIn(0, 100)
        _gestures.update { list ->
            list.map { if (it.id == id) it.copy(strictness = coerced) else it }
        }
        rebuildModels()
        savePersisted()
    }

    override fun startTraining(id: String, examplesWanted: Int) {
        val have = trainedExamples[id]?.size ?: 0
        segmenter.reset()
        _training.value = TrainingState(
            gestureId = id,
            examplesRecorded = have,
            examplesWanted = examplesWanted,
            prompt = "Do the gesture now",
            listening = true,
        )
        updateMotionStreaming()
    }

    override fun stopTraining() {
        _training.value = null
        updateMotionStreaming()
    }

    override fun clearExamples(id: String) {
        trainedExamples[id]?.clear()
        _gestures.update { list ->
            list.map { if (it.id == id) it.copy(examples = 0) else it }
        }
        rebuildModels()
        savePersisted()
    }

    override fun testAction(id: String) {
        val gesture = _gestures.value.firstOrNull { it.id == id } ?: return
        val hit = GestureHit(gesture.id, gesture.name, 1.0, clock())
        _hits.tryEmit(hit)
        scope.launch(ioDispatcher) {
            val result = actionExecutor.execute(gesture.action, hit)
            _actionResults.tryEmit(result)
        }
    }

    override fun setApiSettings(settings: ApiSettings) {
        val token = if (settings.token.isBlank()) {
            UUID.randomUUID().toString().replace("-", "")
        } else {
            settings.token
        }
        val effectiveSettings = settings.copy(token = token)
        _apiSettings.value = effectiveSettings

        apiServer?.stop()
        apiServer = null
        clientCountJob?.cancel()

        if (effectiveSettings.enabled) {
            val server = RingApiServer(
                ring = ring,
                studio = this,
                token = effectiveSettings.token,
                port = effectiveSettings.port,
                lan = effectiveSettings.lan,
                scope = scope,
            )
            server.start()
            apiServer = server

            val urls = RingApiServer.getLocalUrls(server.boundPort, effectiveSettings.lan)
            _apiStatus.value = ApiStatus(running = true, urls = urls, clients = 0)

            clientCountJob = scope.launch {
                server.clientCount.collect { count ->
                    _apiStatus.update { it.copy(clients = count) }
                }
            }
        } else {
            _apiStatus.value = ApiStatus(running = false, urls = emptyList(), clients = 0)
        }
    }
}
