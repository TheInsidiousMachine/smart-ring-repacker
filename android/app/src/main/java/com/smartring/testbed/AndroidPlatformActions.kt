package com.smartring.testbed

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.smartring.gestures.PlatformActions

/** Media keys for gesture actions, via the system audio manager. */
class AndroidPlatformActions(context: Context) : PlatformActions {
    private val am = context.getSystemService(AudioManager::class.java)
    override fun sendMediaKey(key: String): Boolean {
        val code = when (key) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "volume_up" -> { am?.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return am != null }
            "volume_down" -> { am?.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return am != null }
            else -> return false
        }
        val t = SystemClock.uptimeMillis()
        am?.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am?.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return am != null
    }
}
