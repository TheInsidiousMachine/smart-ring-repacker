package com.smartring.testbed

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only capture of one testbed session: every raw TX/RX packet plus the
 * console notes and MARK lines, so live observations can be labeled later.
 * Lives in the app's external files dir; pull headlessly with
 * `adb pull /sdcard/Android/data/com.smartring.testbed/files/sessions`.
 */
class SessionLog private constructor(private val file: File) {
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val path: String get() = file.absolutePath

    @Synchronized
    private fun write(line: String) {
        try {
            FileWriter(file, true).use { it.write("${ts.format(Date())}\t$line\n") }
        } catch (_: Exception) {
        }
    }

    fun raw(dir: String, chr: String, bytes: ByteArray) = write("$dir\t$chr\t${RingProtocol.toHex(bytes)}")
    fun note(msg: String) = write("NOTE\t\t$msg")

    companion object {
        fun open(base: File?): SessionLog? {
            base ?: return null
            val dir = File(base, "sessions").apply { mkdirs() }
            val name = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".tsv"
            return SessionLog(File(dir, name))
        }
    }
}
