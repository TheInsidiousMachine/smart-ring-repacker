package com.smartring.ui.logic

/** In-app summary of the phone API. The full page lives at [PATH]. */
object ApiReference {
    const val PATH = "docs/API.md"
    val BODY = """
        Every call sends Authorization: Bearer <token>. LAN off binds to this phone only.

        WebSocket /events
        Touch, gestures, mode, heartbeat, and motion when requested.

        POST /display   {"text":"HELLO"}
        POST /message   the bump-bump-flick message
        POST /mode      CLOCK, MOUSE, or HEART
        POST /haptic    find-ring buzz
    """.trimIndent()
}
