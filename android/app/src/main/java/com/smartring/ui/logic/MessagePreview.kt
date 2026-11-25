package com.smartring.ui.logic

import com.smartring.core.GestureMessage

/**
 * What the ring's 5-character window shows for a message.
 *
 * [protocolFrames] is a copy of `RingProtocol.messageFrames` (frozen, not imported here).
 * A blank gesture message uploads zero frames. Build 14 plays that as "HI THERE"
 * scrolling once. Keep [protocolFrames] in lockstep with the protocol function.
 */
object MessagePreview {
    const val MAX_FRAMES = 32
    /** What a blank message plays on build 14: one scroll of the built-in text. */
    val BUILTIN = protocolFrames("HI THERE", scroll = true)

    fun protocolFrames(text: String, scroll: Boolean): List<String> {
        val t = text.trim().uppercase().map { if (it.code in 0x20..0x7e) it else ' ' }.joinToString("")
        if (t.isEmpty()) return emptyList()
        if (scroll) {
            val padded = "     $t     "
            return (0..padded.length - 5).map { padded.substring(it, it + 5) }.take(MAX_FRAMES)
        }
        return t.split(Regex(" +")).flatMap { w -> w.chunked(5) }
            .map { w ->
                val pad = 5 - w.length
                " ".repeat(pad / 2) + w + " ".repeat(pad - pad / 2)
            }
            .take(MAX_FRAMES)
    }

    /** Gesture-message frames, with the built-in message substituted for a blank. */
    fun previewFrames(text: String, scroll: Boolean): List<String> =
        protocolFrames(text, scroll).ifEmpty { BUILTIN }

    /** One full play-out, repeated [GestureMessage.plays] times. */
    fun played(message: GestureMessage): List<String> {
        val once = previewFrames(message.text, message.scroll)
        return List(message.plays.coerceIn(1, 5)) { once }.flatten()
    }

    /**
     * "Show now": up to 5 characters stay put, left-aligned. Longer text scrolls
     * the same way a gesture message does.
     */
    fun showNow(text: String): List<String> {
        val t = text.trim().uppercase().map { if (it.code in 0x20..0x7e) it else ' ' }.joinToString("")
        if (t.isEmpty()) return listOf("     ")
        if (t.length <= 5) return listOf(t.padEnd(5))
        return protocolFrames(t, scroll = true)
    }

    fun capped(text: String, scroll: Boolean): Boolean =
        protocolFrames(text, scroll).size >= MAX_FRAMES && text.isNotBlank()
}
