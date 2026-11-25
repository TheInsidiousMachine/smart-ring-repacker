package com.smartring.gestures

import com.smartring.core.ActionResult
import com.smartring.core.GestureAction
import com.smartring.core.GestureHit
import com.smartring.core.RingController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.Locale

class ActionExecutor(
    private val ring: RingController,
    private val platform: PlatformActions,
    private val client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(action: GestureAction, hit: GestureHit): ActionResult = withContext(ioDispatcher) {
        val atMs = clock()
        when (action) {
            is GestureAction.None -> {
                ActionResult(hit.name, "None", true, "none", atMs)
            }
            is GestureAction.RingText -> {
                ring.showText(action.text)
                ActionResult(hit.name, "RingText", true, action.text, atMs)
            }
            is GestureAction.MediaKey -> {
                val ok = platform.sendMediaKey(action.key)
                ActionResult(hit.name, "MediaKey", ok, action.key, atMs)
            }
            is GestureAction.PcKeys -> {
                ActionResult(hit.name, "PcKeys", true, action.keys, atMs)
            }
            is GestureAction.ApiEventOnly -> {
                ActionResult(hit.name, "ApiEventOnly", true, "published", atMs)
            }
            is GestureAction.Webhook -> {
                executeWebhook(action, hit, atMs)
            }
        }
    }

    private fun executeWebhook(action: GestureAction.Webhook, hit: GestureHit, atMs: Long): ActionResult {
        val isoTime = Instant.ofEpochMilli(atMs).toString()
        val confStr = String.format(Locale.US, "%.2f", hit.confidence)

        fun replacePlaceholders(src: String): String =
            src.replace("{gesture}", hit.name)
                .replace("{confidence}", confStr)
                .replace("{time}", isoTime)

        val url = replacePlaceholders(action.url)
        val bodyText = replacePlaceholders(action.body)

        return try {
            val reqBuilder = Request.Builder().url(url)
            for ((k, v) in action.headers) {
                reqBuilder.addHeader(k, replacePlaceholders(v))
            }

            val methodUpper = action.method.uppercase(Locale.US)
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()

            when (methodUpper) {
                "GET", "HEAD" -> reqBuilder.method(methodUpper, null)
                "POST", "PUT", "PATCH", "DELETE" -> {
                    val reqBody = bodyText.toRequestBody(jsonMediaType)
                    reqBuilder.method(methodUpper, reqBody)
                }
                else -> {
                    val reqBody = bodyText.toRequestBody(jsonMediaType)
                    reqBuilder.method(methodUpper, reqBody)
                }
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                ActionResult(
                    gestureName = hit.name,
                    action = "Webhook",
                    ok = resp.isSuccessful,
                    detail = "HTTP ${resp.code}",
                    atMs = atMs,
                )
            }
        } catch (e: IOException) {
            ActionResult(
                gestureName = hit.name,
                action = "Webhook",
                ok = false,
                detail = "${e.javaClass.simpleName}: ${e.message}",
                atMs = atMs,
            )
        } catch (e: Exception) {
            ActionResult(
                gestureName = hit.name,
                action = "Webhook",
                ok = false,
                detail = "${e.javaClass.simpleName}: ${e.message}",
                atMs = atMs,
            )
        }
    }
}
