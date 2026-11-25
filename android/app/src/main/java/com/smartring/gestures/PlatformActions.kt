package com.smartring.gestures

/**
 * Android platform actions that require Android system APIs (media keys, etc.).
 * Abstracted behind this interface so RealGestureStudio and all action logic can run in pure JVM unit tests.
 */
interface PlatformActions {
    /**
     * Trigger an Android media key event.
     * Common keys: "play_pause", "next", "previous", "volume_up", "volume_down".
     * Returns true if sent successfully.
     */
    fun sendMediaKey(key: String): Boolean
}

class NoOpPlatformActions : PlatformActions {
    val dispatchedKeys = mutableListOf<String>()

    override fun sendMediaKey(key: String): Boolean {
        synchronized(dispatchedKeys) {
            dispatchedKeys += key
        }
        return true
    }
}
