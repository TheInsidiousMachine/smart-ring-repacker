package com.smartring.testbed

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Developer tools: raw protocol buttons + live log. Uses the shared RingLink. */
class MainActivity : AppCompatActivity(), RingLink.Listener {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var markCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        RingLink.init(this)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        fun bind(id: Int, label: String, packet: () -> ByteArray) =
            findViewById<Button>(id).setOnClickListener { RingLink.send(packet(), label) }

        findViewById<Button>(R.id.connectButton).setOnClickListener { RingLink.connect() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { RingLink.disconnect() }

        bind(R.id.enableMouseBtn, "HID mode 1") { RingProtocol.setVideoHid(1, true) }
        bind(R.id.enableEbookBtn, "HID mode 2") { RingProtocol.setVideoHid(2, true) }
        bind(R.id.hidMusicBtn, "HID mode 3") { RingProtocol.setVideoHid(3, true) }
        bind(R.id.disableMouseBtn, "HID mode 0") { RingProtocol.setVideoHid(0, false) }
        bind(R.id.queryMouseBtn, "query HID mode") { RingProtocol.queryVideoHid() }

        bind(R.id.ledLevel3Btn, "LED 3") { RingProtocol.setLedLevel(true, 3) }
        bind(R.id.ledLevel1Btn, "LED 1") { RingProtocol.setLedLevel(true, 1) }
        bind(R.id.ledOffBtn, "LED off") { RingProtocol.setLedLevel(false, 0) }
        bind(R.id.queryLedBtn, "query LED") { RingProtocol.queryLedLevel() }
        bind(R.id.timeout15sBtn, "screen-on 15s") { RingProtocol.setScreenTimeout(15) }

        bind(R.id.vibrateBtn, "vibration pref 2x2") { RingProtocol.setVibrationPref(2, 2) }
        bind(R.id.queryVibBtn, "query vibration") { RingProtocol.queryVibration() }
        bind(R.id.findDeviceBtn, "find device") { RingProtocol.findDevice() }
        bind(R.id.factoryStateBtn, "factory state") { RingProtocol.queryFactoryState() }
        bind(R.id.wearLeftBtn, "wear left") { RingProtocol.setWearHand(false) }
        bind(R.id.wearRightBtn, "wear right") { RingProtocol.setWearHand(true) }

        bind(R.id.startHrButton, "start HR") { RingProtocol.startMeasurement(RingProtocol.SENSOR_HEART_RATE) }
        bind(R.id.stopHrButton, "stop HR") { RingProtocol.stopMeasurement(RingProtocol.SENSOR_HEART_RATE) }
        bind(R.id.startSpo2Button, "start SpO2") { RingProtocol.startMeasurement(RingProtocol.SENSOR_SPO2) }
        bind(R.id.stopSpo2Button, "stop SpO2") { RingProtocol.stopMeasurement(RingProtocol.SENSOR_SPO2) }
        bind(R.id.syncStepsButton, "sync steps") { RingProtocol.syncSteps() }
        bind(R.id.queryFeaturesButton, "query features") { RingProtocol.queryDeviceSupport() }

        bind(R.id.armButton, "camera/tap mode on") { RingProtocol.cameraMode(true) }
        bind(R.id.disarmButton, "camera/tap mode off") { RingProtocol.cameraMode(false) }
        bind(R.id.countOnBtn, "counter mode on") { RingProtocol.setCountMode(true) }
        bind(R.id.countOffBtn, "counter mode off") { RingProtocol.setCountMode(false) }

        findViewById<Button>(R.id.firmwareUpdateBtn).setOnClickListener { confirmFirmwareUpdate() }
        findViewById<Button>(R.id.powerOffBtn).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Power off the ring?")
                .setMessage("Sends the stock app's power-off command {2,34,0,1}. Put the ring on its charger to turn it back on.")
                .setPositiveButton("Power off") { _, _ -> RingLink.send(RingProtocol.powerOff(), "power off") }
                .setNegativeButton("Cancel", null).show()
        }

        val titleInput = findViewById<EditText>(R.id.titleInput)
        val contentInput = findViewById<EditText>(R.id.contentInput)
        findViewById<Button>(R.id.pushTextButton).setOnClickListener {
            val t = titleInput.text.toString().ifBlank { "Ring" }
            val c = contentInput.text.toString().ifBlank { "Hello" }
            RingLink.send(RingProtocol.pushMessage(t, c), "push \"$t\" / \"$c\"")
        }

        findViewById<Button>(R.id.markButton).setOnClickListener { markCount++; RingLink.log("MARK $markCount") }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener { logText.text = "" }
        RingLink.log("Session log: ${RingLink.session?.path ?: "unavailable"}")
    }

    /** The user must type the first 8 hex chars of the image hash to confirm this exact write. */
    private fun confirmFirmwareUpdate() {
        val dir = getExternalFilesDir("firmware") ?: return RingLink.log("No firmware dir")
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name }.orEmpty()
        val candidates = files.map { it to PxiOtaSession.sha256(it.readBytes()) }
            .filter { it.second in PxiOtaSession.ALLOWED_SHA256 }
        if (candidates.isEmpty()) {
            RingLink.log("No allowlisted .bin in ${dir.absolutePath} (found ${files.map { it.name }})")
            return
        }
        if (candidates.size == 1) return confirmWrite(candidates[0].first, candidates[0].second)
        android.app.AlertDialog.Builder(this)
            .setTitle("Which firmware?")
            .setItems(candidates.map { PxiOtaSession.ALLOWED_SHA256[it.second] ?: it.first.name }.toTypedArray()) { _, i ->
                confirmWrite(candidates[i].first, candidates[i].second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmWrite(file: java.io.File, hash: String) {
        val input = EditText(this).apply { hint = "type ${hash.take(8)} to confirm" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Write firmware to ring?")
            .setMessage(
                "${PxiOtaSession.ALLOWED_SHA256[hash]}\n${file.name}\n${file.length()} bytes\nsha256 $hash\n\n" +
                    "Ring on its charger, phone close by, RWFit closed, don't touch the ring until it reboots. " +
                    "If anything fails before the ring verifies the image, the current firmware keeps running."
            )
            .setView(input)
            .setPositiveButton("Write") { _, _ ->
                if (input.text.toString().trim().lowercase() != hash.take(8)) {
                    RingLink.log("Confirmation text did not match — nothing written")
                } else {
                    RingLink.startFirmwareUpdate(file.readBytes())?.let { RingLink.log(it) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStart() { super.onStart(); RingLink.addListener(this) }
    override fun onStop() { RingLink.removeListener(this); super.onStop() }

    override fun onState(state: RingLink.State, detail: String) { statusText.text = detail }

    override fun onLog(line: String) {
        logText.append("[${timeFmt.format(Date())}] $line\n")
    }
}
