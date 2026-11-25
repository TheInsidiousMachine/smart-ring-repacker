package com.smartring.testbed

import android.content.Context

/** Persisted "gesture → action" rules and saved screen messages. */
object GestureRules {
    enum class Type { NONE, MESSAGE, BUZZ }
    data class Action(val type: Type, val text: String = "") {
        override fun toString() = when (type) {
            Type.NONE -> "nothing"
            Type.BUZZ -> "buzz"
            Type.MESSAGE -> "show \"$text\""
        }
    }

    /** Tap patterns available on stock firmware. Motion gestures need custom firmware. */
    val GESTURES = listOf("double", "triple", "multi4")
    val LABELS = mapOf("double" to "Double tap", "triple" to "Triple tap", "multi4" to "Quad tap")

    private fun prefs(c: Context) = c.getSharedPreferences("gesture_rules", Context.MODE_PRIVATE)

    fun get(c: Context, gesture: String): Action {
        val p = prefs(c)
        val type = runCatching { Type.valueOf(p.getString("$gesture.type", "NONE")!!) }.getOrDefault(Type.NONE)
        return Action(type, p.getString("$gesture.text", "") ?: "")
    }

    fun set(c: Context, gesture: String, a: Action) {
        prefs(c).edit().putString("$gesture.type", a.type.name).putString("$gesture.text", a.text).apply()
    }

    fun savedMessages(c: Context): List<String> =
        prefs(c).getString("saved", null)?.split('\u001F')?.filter { it.isNotEmpty() } ?: emptyList()

    fun saveMessage(c: Context, msg: String) {
        val list = (savedMessages(c) - msg + msg).takeLast(12)
        prefs(c).edit().putString("saved", list.joinToString("\u001F")).apply()
    }

    fun removeMessage(c: Context, msg: String) {
        prefs(c).edit().putString("saved", (savedMessages(c) - msg).joinToString("\u001F")).apply()
    }
}
