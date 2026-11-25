package com.smartring.ui.prefs

import android.content.Context

/** Phone-side UI state. Dev mode is ours; saved screen messages share the old chip list. */
interface UiPreferences {
    var devMode: Boolean
    fun savedMessages(): List<String>
    fun saveMessage(message: String)
    fun removeMessage(message: String)
}

class SharedUiPreferences(context: Context) : UiPreferences {
    private val dev = context.applicationContext.getSharedPreferences("smartring_ui", Context.MODE_PRIVATE)
    private val saved = context.applicationContext.getSharedPreferences("gesture_rules", Context.MODE_PRIVATE)

    override var devMode: Boolean
        get() = dev.getBoolean(KEY_DEV, false)
        set(value) {
            dev.edit().putBoolean(KEY_DEV, value).apply()
        }

    override fun savedMessages(): List<String> =
        saved.getString(KEY_SAVED, null)?.split('\u001F')?.filter { it.isNotEmpty() } ?: emptyList()

    override fun saveMessage(message: String) {
        val list = (savedMessages() - message + message).takeLast(MAX_SAVED)
        saved.edit().putString(KEY_SAVED, list.joinToString("\u001F")).apply()
    }

    override fun removeMessage(message: String) {
        saved.edit().putString(KEY_SAVED, (savedMessages() - message).joinToString("\u001F")).apply()
    }

    private companion object {
        const val KEY_DEV = "dev_mode"
        const val KEY_SAVED = "saved"
        const val MAX_SAVED = 12
    }
}

class MemoryUiPreferences : UiPreferences {
    override var devMode: Boolean = false
    private val saved = ArrayList<String>()

    override fun savedMessages(): List<String> = saved.toList()

    override fun saveMessage(message: String) {
        saved.remove(message)
        saved.add(message)
        while (saved.size > 12) saved.removeAt(0)
    }

    override fun removeMessage(message: String) {
        saved.remove(message)
    }
}
