package com.smartring.api

import com.smartring.core.ActionResult
import com.smartring.core.FakeGestureStudio
import com.smartring.core.FakeRingController
import com.smartring.core.GestureHit
import com.smartring.core.RingEvent
import com.smartring.core.RingMode
import com.smartring.core.TouchKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class RingApiServerTest {

    private lateinit var ring: FakeRingController
    private lateinit var studio: FakeGestureStudio
    private lateinit var server: RingApiServer
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val token = "test_bearer_token_12345"
    private var port = 0
    private var baseUrl = ""

    @Before
    fun setUp() {
        ring = FakeRingController()
        studio = FakeGestureStudio()
        // Start on ephemeral port (0)
        server = RingApiServer(
            ring = ring,
            studio = studio,
            token = token,
            port = 0,
            lan = false,
        )
        server.start()
        port = server.boundPort
        baseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testAuthenticationEnforced() {
        // 1. Without auth header -> 401
        val reqNoAuth = Request.Builder().url("$baseUrl/v1/status").build()
        client.newCall(reqNoAuth).execute().use { resp ->
            assertEquals(401, resp.code)
            assertEquals("Bearer", resp.header("WWW-Authenticate"))
            assertTrue(resp.body?.string()?.contains("unauthorized") == true)
        }

        // 2. With invalid token -> 401
        val reqBadAuth = Request.Builder()
            .url("$baseUrl/v1/status")
            .header("Authorization", "Bearer wrong_token")
            .build()
        client.newCall(reqBadAuth).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // 3. With valid token -> 200
        val reqGoodAuth = Request.Builder()
            .url("$baseUrl/v1/status")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(reqGoodAuth).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body?.string() ?: ""
            assertTrue(body.contains(""""connection""""))
            assertTrue(body.contains(""""status""""))
        }
    }

    @Test
    fun testDisplayEndpointUpdatesRingController() {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = """{"text":"RING1"}""".toRequestBody(jsonMediaType)

        val req = Request.Builder()
            .url("$baseUrl/v1/display")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val respBody = resp.body?.string() ?: ""
            assertTrue(respBody.contains(""""ok":true"""))
        }

        assertTrue("FakeRingController should have received showText", ring.commands.contains("showText RING1"))
        assertEquals("RING1", ring.screen)
    }

    @Test
    fun testModeEndpoint() {
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = """{"mode":"MOUSE"}""".toRequestBody(jsonMediaType)

        val req = Request.Builder()
            .url("$baseUrl/v1/mode")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body?.string()?.contains(""""mode":"MOUSE"""") == true)
        }

        assertTrue(ring.commands.contains("setMode MOUSE"))
    }

    @Test
    fun testClockSyncAndFind() {
        val emptyBody = "".toRequestBody("application/json".toMediaType())

        // clock sync
        val syncReq = Request.Builder()
            .url("$baseUrl/v1/clock/sync")
            .header("Authorization", "Bearer $token")
            .post(emptyBody)
            .build()
        client.newCall(syncReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertTrue(ring.commands.contains("syncClock"))

        // find ring
        val findReq = Request.Builder()
            .url("$baseUrl/v1/find")
            .header("Authorization", "Bearer $token")
            .post(emptyBody)
            .build()
        client.newCall(findReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertTrue(ring.commands.contains("findRing"))
    }

    @Test
    fun testGesturesEndpoint() {
        val req = Request.Builder()
            .url("$baseUrl/v1/gestures")
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body?.string() ?: ""
            assertTrue(body.contains("Swipe right"))
            assertTrue(body.contains("Double knock"))
        }
    }

    @Test
    fun testEventsWebSocketStream() {
        val wsUrl = "ws://127.0.0.1:$port/v1/events"
        val wsReq = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        val messages = mutableListOf<String>()
        val connectedLatch = CountDownLatch(1)
        val eventReceivedLatch = CountDownLatch(1)

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectedLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(messages) {
                    messages += text
                }
                eventReceivedLatch.countDown()
            }
        }

        val ws = client.newWebSocket(wsReq, wsListener)
        assertTrue("WebSocket should connect", connectedLatch.await(3, TimeUnit.SECONDS))

        // Ensure server has registered the connected client
        val deadline = System.currentTimeMillis() + 3000
        while (server.clientCount.value < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("Server clientCount should be >= 1", server.clientCount.value >= 1)

        // Inject event on fake ring
        ring.emitEvent(RingEvent.Touch(TouchKind.TAP, 1700000000000L))

        assertTrue("Should receive event on WebSocket", eventReceivedLatch.await(3, TimeUnit.SECONDS))
        val msg = synchronized(messages) { messages.firstOrNull() ?: "" }
        println("Received WebSocket message: $msg")
        assertTrue(msg.contains(""""event":"touch""""))
        assertTrue(msg.contains(""""kind":"TAP""""))

        ws.close(1000, "done")
    }
}
