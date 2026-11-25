package com.smartring.ui.logic

/**
 * Stock command bodies the old developer screen sent, expressed as the hex
 * [com.smartring.core.RingController.sendRaw] accepts (group, subcommand, flag, payload).
 * Factory restore `{2,34,0,2}` is intentionally absent.
 */
data class DevCommand(
    val group: String,
    val label: String,
    val bodies: List<String>,
    val caution: String? = null,
)

object DevCommands {
    private fun hex(vararg bytes: Int): String = bytes.joinToString(" ") { "%02x".format(it) }

    private fun stop(sensor: Int) = hex(6, 9, 0, sensor, 5, 0)

    val reset = DevCommand(
        group = "Recovery",
        label = "Reset ring",
        bodies = listOf(
            hex(2, 103, 16),
            hex(2, 103, 0, 0),
            hex(6, 1, 0, 0),
            hex(2, 100, 0, 0, 0),
            stop(3), stop(9), stop(10), stop(13), stop(8),
            hex(2, 100, 16),
        ),
        caution = "Stops sensors and returns touch to normal. The ring should be connected.",
    )

    val all: List<DevCommand> = listOf(
        DevCommand("HID", "HID off", listOf(hex(2, 100, 0, 0, 0))),
        DevCommand("HID", "HID short video", listOf(hex(2, 100, 0, 1, 1))),
        DevCommand("HID", "HID e-book", listOf(hex(2, 100, 0, 2, 1))),
        DevCommand("HID", "HID music", listOf(hex(2, 100, 0, 3, 1))),
        DevCommand("HID", "Query HID", listOf(hex(2, 100, 16))),
        DevCommand("Display", "LED max", listOf(hex(2, 102, 0, 1, 3))),
        DevCommand("Display", "LED low", listOf(hex(2, 102, 0, 1, 1))),
        DevCommand("Display", "LED off", listOf(hex(2, 102, 0, 0, 0))),
        DevCommand("Display", "Query brightness", listOf(hex(2, 102, 16))),
        DevCommand("Display", "Timeout 15s", listOf(hex(2, 8, 0, 15))),
        DevCommand("Haptics", "Vibration pref 2×2", listOf(hex(2, 11, 0, 2, 2))),
        DevCommand("Haptics", "Query vibration", listOf(hex(2, 11, 16))),
        DevCommand("Haptics", "Find device", listOf(hex(2, 52, 0, 1))),
        DevCommand("Wear", "Wear left", listOf(hex(2, 104, 0, 0))),
        DevCommand("Wear", "Wear right", listOf(hex(2, 104, 0, 1))),
        DevCommand(
            "Factory",
            "Factory self-test",
            listOf(hex(2, 112, 16)),
            caution = "This query has started the ring's factory self-test before (PPG LED on, UI stuck). Send it only if you mean to.",
        ),
        DevCommand("Factory", "Factory mode off", listOf(hex(2, 103, 0, 0))),
        DevCommand("Factory", "Query factory mode", listOf(hex(2, 103, 16))),
        DevCommand("Sensors", "Start heart rate", listOf(hex(6, 9, 0, 3, 5, 1))),
        DevCommand("Sensors", "Stop heart rate", listOf(hex(6, 9, 0, 3, 5, 0))),
        DevCommand("Sensors", "Start SpO2", listOf(hex(6, 9, 0, 9, 5, 1))),
        DevCommand("Sensors", "Stop SpO2", listOf(hex(6, 9, 0, 9, 5, 0))),
        DevCommand("Sensors", "Sync steps", listOf(hex(5, 26, 16))),
        DevCommand("Sensors", "Query features", listOf(hex(2, 99, 16))),
        DevCommand("Sensors", "Query battery", listOf(hex(2, 3, 16))),
        DevCommand("Sensors", "Query firmware", listOf(hex(2, 4, 16))),
        DevCommand("Touch", "Tap events on", listOf(hex(6, 1, 0, 1))),
        DevCommand("Touch", "Tap events off", listOf(hex(6, 1, 0, 0))),
        DevCommand("Touch", "Counter on", listOf(hex(2, 120, 0, 1))),
        DevCommand("Touch", "Counter off", listOf(hex(2, 120, 0, 0))),
        reset,
    )

    val groups: List<String> = all.map { it.group }.distinct()
}
