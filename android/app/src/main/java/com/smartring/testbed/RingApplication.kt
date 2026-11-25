package com.smartring.testbed

import android.app.Application
import com.smartring.core.RingServices

/** Wires the real implementations behind the UI's RingServices. */
class RingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Robolectric UI tests keep the fakes.
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return
        val ring = RealRingController(this)
        RingServices.ring = ring
        RingServices.studio = com.smartring.gestures.RealGestureStudio(
            ring, java.io.File(filesDir, "gestures").apply { mkdirs() },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
            AndroidPlatformActions(this),
        )
    }
}
