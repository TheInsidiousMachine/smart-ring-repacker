package com.smartring.api

import com.smartring.core.ActionResult
import com.smartring.core.ConnectionState
import com.smartring.core.CustomGesture
import com.smartring.core.GestureAction
import com.smartring.core.GestureHit
import com.smartring.core.GestureMessage
import com.smartring.core.GestureStudio
import com.smartring.core.RingController
import com.smartring.core.RingEvent
import com.smartring.core.RingMode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HTTP & WebSocket API Server for the Smart Ring Repacker app.
 * Provides authenticated control for the companion daemon, PC bridge, external scripts, and local network apps.
 */
class RingApiServer(
    private val ring: RingController,
    private val studio: GestureStudio,
    private val token: String,
    private val port: Int,
    private val lan: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    /** Constant-time token check. */
    private fun tokenMatches(t: String?): Boolean =
        t != null && java.security.MessageDigest.isEqual(t.toByteArray(), token.toByteArray())

    private var engine: ApplicationEngine? = null
    private val activeClients = AtomicInteger(0)
    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()
    private var resolvedPort: Int = port

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val wsSubscribers = ConcurrentHashMap.newKeySet<io.ktor.websocket.WebSocketSession>()
    private val motionSubscribers = ConcurrentHashMap.newKeySet<io.ktor.websocket.WebSocketSession>()

    private var eventsCollectorJob: Job? = null
    private var hitsCollectorJob: Job? = null
    private var actionsCollectorJob: Job? = null
    private var motionCollectorJob: Job? = null

    val boundPort: Int
        get() = resolvedPort

    fun start() {
        val host = if (lan) "0.0.0.0" else "127.0.0.1"
        val server = embeddedServer(CIO, port = port, host = host) {
            install(WebSockets)

            routing {
                // Bearer auth helper
                suspend fun ApplicationCall.authenticate(): Boolean {
                    val authHeader = request.header("Authorization")
                    val queryToken = request.queryParameters["token"]
                    val providedToken = when {
                        authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) -> {
                            authHeader.substring(7).trim()
                        }
                        authHeader != null -> authHeader.trim()
                        queryToken != null -> queryToken.trim()
                        else -> null
                    }
                    if (providedToken.isNullOrEmpty() || !tokenMatches(providedToken)) {
                        response.header("WWW-Authenticate", "Bearer")
                        respondText(
                            text = """{"error":"unauthorized"}""",
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.Unauthorized,
                        )
                        return false
                    }
                    return true
                }

                get("/v1/status") {
                    if (!call.authenticate()) return@get
                    val conn = ring.connection.value
                    val st = ring.status.value
                    val statusJson = buildString {
                        append("{")
                        append(""""connection":{"state":"${conn.state.name}","deviceName":${jsonString(conn.deviceName)},"detail":${jsonString(conn.detail)}},""")
                        append(""""status":{""")
                        append(""""batteryPercent":${st.batteryPercent ?: "null"},""")
                        append(""""charging":${st.charging ?: "null"},""")
                        append(""""firmwareVersion":${jsonString(st.firmwareVersion)},""")
                        append(""""customBuild":${jsonString(st.customBuild)},""")
                        append(""""mode":"${st.mode.name}",""")
                        append(""""clockSyncedAtMs":${st.clockSyncedAtMs ?: "null"},""")
                        append(""""gestureDetectorOn":${st.gestureDetectorOn ?: "null"},""")
                        append(""""gestureFired":${st.gestureFired},""")
                        append(""""gestureArmed":${st.gestureArmed},""")
                        append(""""heartBpm":${st.heartBpm ?: "null"},""")
                        append(""""screenBrightness":${st.screenBrightness ?: "null"},""")
                        append(""""screenTimeoutSec":${st.screenTimeoutSec ?: "null"}""")
                        append("},")
                        append(""""listening":${studio.listening.value}""")
                        append("}")
                    }
                    call.respondText(statusJson, ContentType.Application.Json)
                }

                post("/v1/display") {
                    if (!call.authenticate()) return@post
                    val body = call.receiveText()
                    val text = try {
                        val parsed = json.parseToJsonElement(body).jsonObject
                        parsed["text"]?.jsonPrimitive?.content ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    ring.showText(text)
                    call.respondText("""{"ok":true,"text":${jsonString(text)}}""", ContentType.Application.Json)
                }

                get("/v1/message") {
                    if (!call.authenticate()) return@get
                    val msg = ring.gestureMessage.value
                    val msgJson = buildString {
                        append("{")
                        append(""""text":${jsonString(msg.text)},""")
                        append(""""scroll":${msg.scroll},""")
                        append(""""msPerStep":${msg.msPerStep},""")
                        append(""""plays":${msg.plays}""")
                        append("}")
                    }
                    call.respondText(msgJson, ContentType.Application.Json)
                }

                post("/v1/message") {
                    if (!call.authenticate()) return@post
                    val body = call.receiveText()
                    try {
                        val obj = json.parseToJsonElement(body).jsonObject
                        val current = ring.gestureMessage.value
                        val newMsg = GestureMessage(
                            text = obj["text"]?.jsonPrimitive?.content ?: current.text,
                            scroll = obj["scroll"]?.jsonPrimitive?.booleanOrNull ?: current.scroll,
                            msPerStep = obj["msPerStep"]?.jsonPrimitive?.intOrNull ?: current.msPerStep,
                            plays = obj["plays"]?.jsonPrimitive?.intOrNull ?: current.plays,
                        )
                        ring.setGestureMessage(newMsg)
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText("""{"ok":false,"error":${jsonString(e.message)}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }

                post("/v1/mode") {
                    if (!call.authenticate()) return@post
                    val body = call.receiveText()
                    try {
                        val obj = json.parseToJsonElement(body).jsonObject
                        val modeStr = obj["mode"]?.jsonPrimitive?.content?.uppercase() ?: ""
                        val mode = RingMode.valueOf(modeStr)
                        ring.setMode(mode)
                        call.respondText("""{"ok":true,"mode":"${mode.name}"}""", ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText("""{"ok":false,"error":${jsonString("Invalid mode: ${e.message}")}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }

                post("/v1/clock/sync") {
                    if (!call.authenticate()) return@post
                    ring.syncClock()
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }

                post("/v1/find") {
                    if (!call.authenticate()) return@post
                    ring.findRing()
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }

                get("/v1/gestures") {
                    if (!call.authenticate()) return@get
                    val gesturesList = studio.gestures.value
                    val serialized = json.encodeToString(gesturesList)
                    call.respondText(serialized, ContentType.Application.Json)
                }

                webSocket("/v1/events") {
                    val authHeader = call.request.header("Authorization")
                    val queryToken = call.request.queryParameters["token"]
                    val providedToken = when {
                        authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) -> authHeader.substring(7).trim()
                        authHeader != null -> authHeader.trim()
                        queryToken != null -> queryToken.trim()
                        else -> null
                    }
                    if (providedToken.isNullOrEmpty() || !tokenMatches(providedToken)) {
                        close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }

                    wsSubscribers.add(this)
                    val count = activeClients.incrementAndGet()
                    _clientCount.value = count

                    try {
                        for (frame in incoming) {
                            // Can handle incoming client commands or pings if needed
                        }
                    } finally {
                        wsSubscribers.remove(this)
                        val remaining = activeClients.decrementAndGet()
                        _clientCount.value = remaining
                    }
                }

                webSocket("/v1/motion") {
                    val authHeader = call.request.header("Authorization")
                    val queryToken = call.request.queryParameters["token"]
                    val providedToken = when {
                        authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) -> authHeader.substring(7).trim()
                        authHeader != null -> authHeader.trim()
                        queryToken != null -> queryToken.trim()
                        else -> null
                    }
                    if (providedToken.isNullOrEmpty() || !tokenMatches(providedToken)) {
                        close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }

                    motionSubscribers.add(this)
                    activeClients.incrementAndGet()
                    _clientCount.value = activeClients.get()

                    try {
                        for (frame in incoming) {
                            // incoming
                        }
                    } finally {
                        motionSubscribers.remove(this)
                        _clientCount.value = activeClients.decrementAndGet()
                    }
                }
            }
        }
        val started = server.start(wait = false)
        engine = started
        if (port == 0) {
            resolvedPort = runBlocking {
                started.resolvedConnectors().firstOrNull()?.port ?: port
            }
        } else {
            resolvedPort = port
        }

        startBroadcasters()
    }

    private fun startBroadcasters() {
        eventsCollectorJob = scope.launch {
            ring.events.collect { event ->
                val payload = when (event) {
                    is RingEvent.Touch -> """{"event":"touch","kind":"${event.kind.name}","atMs":${event.atMs}""" + (event.ringTick?.let { ""","ringTick":$it""" } ?: "") + "}"
                    is RingEvent.ModeChanged -> """{"event":"mode_changed","mode":"${event.mode.name}","atMs":${event.atMs}}"""
                    is RingEvent.GestureArmed -> """{"event":"gesture_armed","atMs":${event.atMs}}"""
                    is RingEvent.GestureFired -> """{"event":"gesture_fired","atMs":${event.atMs}}"""
                    is RingEvent.HeartBeat -> """{"event":"heart_beat","bpm":${event.bpm},"atMs":${event.atMs}}"""
                    is RingEvent.Info -> """{"event":"info","text":${jsonString(event.text)},"atMs":${event.atMs}}"""
                }
                broadcastEvent(payload)
            }
        }

        hitsCollectorJob = scope.launch {
            studio.hits.collect { hit ->
                val payload = """{"event":"gesture_hit","gestureId":${jsonString(hit.gestureId)},"name":${jsonString(hit.name)},"confidence":${hit.confidence},"atMs":${hit.atMs}}"""
                broadcastEvent(payload)
            }
        }

        actionsCollectorJob = scope.launch {
            studio.actionResults.collect { result ->
                if (result.action == "PcKeys") {
                    val payload = """{"event":"pc_keys","gestureName":${jsonString(result.gestureName)},"keys":${jsonString(result.detail)},"atMs":${result.atMs}}"""
                    broadcastEvent(payload)
                } else if (result.action == "ApiEventOnly") {
                    val payload = """{"event":"api_event","gestureName":${jsonString(result.gestureName)},"atMs":${result.atMs}}"""
                    broadcastEvent(payload)
                }
            }
        }

        motionCollectorJob = scope.launch {
            ring.motion.collect { batch ->
                if (motionSubscribers.isNotEmpty()) {
                    val samplesJson = batch.samples.joinToString(",", "[", "]") { s -> "[${s[0]},${s[1]},${s[2]}]" }
                    val payload = """{"event":"motion","seq":${batch.seq},"samples":$samplesJson,"receivedAtMs":${batch.receivedAtMs}}"""
                    for (session in motionSubscribers) {
                        try {
                            if (session.isActive) session.send(Frame.Text(payload))
                        } catch (e: Exception) {
                            // ignore dropped frame
                        }
                    }
                }
            }
        }
    }

    private fun broadcastEvent(jsonPayload: String) {
        val frame = Frame.Text(jsonPayload)
        for (session in wsSubscribers) {
            try {
                if (session.isActive) {
                    session.outgoing.trySend(frame)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun stop() {
        eventsCollectorJob?.cancel()
        hitsCollectorJob?.cancel()
        actionsCollectorJob?.cancel()
        motionCollectorJob?.cancel()

        for (session in wsSubscribers) {
            scope.launch {
                runCatching { session.close() }
            }
        }
        wsSubscribers.clear()

        for (session in motionSubscribers) {
            scope.launch {
                runCatching { session.close() }
            }
        }
        motionSubscribers.clear()

        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }

    companion object {
        fun jsonString(s: String?): String =
            if (s == null) "null" else JsonPrimitive(s).toString()

        /**
         * Enumerate local IP addresses for generating discoverable URLs.
         */
        fun getLocalUrls(port: Int, lan: Boolean): List<String> {
            val list = mutableListOf<String>()
            list += "http://127.0.0.1:$port"
            list += "http://localhost:$port"
            if (lan) {
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (iface.isLoopback || !iface.isUp) continue
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                list += "http://${addr.hostAddress}:$port"
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore network discovery issues
                }
            }
            return list.distinct()
        }
    }
}
