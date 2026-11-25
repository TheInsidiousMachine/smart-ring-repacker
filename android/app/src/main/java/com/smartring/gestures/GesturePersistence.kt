package com.smartring.gestures

import com.smartring.core.CustomGesture
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredExampleData(
    val raw: List<List<Int>>,
    val length: Int,
    val peakJerk: Float,
)

@Serializable
data class StoredGestureEntry(
    val gesture: CustomGesture,
    val examples: List<StoredExampleData>,
)

@Serializable
data class StoredGesturesPayload(
    val entries: List<StoredGestureEntry>,
)

object GesturePersistence {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(file: File): List<StoredGestureEntry> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val content = file.readText()
            val payload = json.decodeFromString<StoredGesturesPayload>(content)
            payload.entries
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(file: File, entries: List<StoredGestureEntry>) {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val payload = StoredGesturesPayload(entries)
            val text = json.encodeToString(payload)
            file.writeText(text)
        } catch (e: Exception) {
            // Silently continue or log if error
        }
    }
}
