package com.smartring.testbed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class HomeActivity : AppCompatActivity(), RingLink.Listener {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var messageInput: TextInputEditText
    private lateinit var savedChips: ChipGroup
    private lateinit var brightness: Slider
    private lateinit var mouseToggle: MaterialButtonToggleGroup
    private lateinit var listenSwitch: SwitchMaterial
    private lateinit var lastGesture: TextView
    private var updatingUi = false

    private val modeButtons by lazy {
        mapOf(R.id.mouseOff to 0, R.id.mouseClicker to 1, R.id.mousePages to 2, R.id.mouseMusic to 3)
    }

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.all { it }) RingLink.connect() else toast("Bluetooth permission is needed")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        RingLink.init(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        messageInput = findViewById(R.id.messageInput)
        savedChips = findViewById(R.id.savedChips)
        brightness = findViewById(R.id.brightnessSlider)
        mouseToggle = findViewById(R.id.mouseToggle)
        listenSwitch = findViewById(R.id.listenSwitch)
        lastGesture = findViewById(R.id.lastGesture)

        connectButton.setOnClickListener {
            if (RingLink.state == RingLink.State.DISCONNECTED) connectWithPermissions() else RingLink.disconnect()
        }

        // Screen
        findViewById<MaterialButton>(R.id.sendButton).setOnClickListener { sendTyped() }
        messageInput.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() } }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            val t = messageInput.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) { GestureRules.saveMessage(this, t); renderSaved() }
        }
        brightness.addOnChangeListener { _, v, fromUser ->
            if (fromUser) RingLink.send(RingProtocol.setLedLevel(v > 0, v.toInt()), "brightness ${v.toInt()}")
        }
        renderSaved()

        // Mouse
        mouseToggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingUi) modeButtons[id]?.let {
                RingLink.send(RingProtocol.setVideoHid(it, it != 0), "mouse mode $it")
            }
        }

        setupRecorder()

        // Bump-bump-flick
        findViewById<SwitchMaterial>(R.id.gestSwitch).setOnCheckedChangeListener { _, on ->
            if (!updatingUi) RingLink.send(RingProtocol.customGesture(on), if (on) "gesture detector on" else "gesture detector off")
        }
        val gText = findViewById<TextInputEditText>(R.id.gestText)
        val gScroll = findViewById<android.widget.RadioButton>(R.id.gestScroll)
        val gSpeed = findViewById<Slider>(R.id.gestSpeed)
        val gReps = findViewById<Slider>(R.id.gestReps)
        fun gLabels() {
            findViewById<TextView>(R.id.gestSpeedLabel).text = "Speed: ${gSpeed.value.toInt()} ms per step"
            findViewById<TextView>(R.id.gestRepsLabel).text = "Play ${gReps.value.toInt()} time${if (gReps.value.toInt() == 1) "" else "s"}"
        }
        RingLink.savedMessage()?.let { m ->
            gText.setText(m.text); gScroll.isChecked = m.scroll
            if (!m.scroll) findViewById<android.widget.RadioButton>(R.id.gestWords).isChecked = true
            gSpeed.value = m.msPerFrame.coerceIn(60, 1500).let { it - (it - 60) % 20 }.toFloat()
            gReps.value = m.reps.coerceIn(1, 5).toFloat()
        }
        gLabels()
        gSpeed.addOnChangeListener { _, _, _ -> gLabels() }
        gReps.addOnChangeListener { _, _, _ -> gLabels() }
        findViewById<MaterialButton>(R.id.gestSave).setOnClickListener {
            if (RingLink.state != RingLink.State.CONNECTED) { toast("Not connected"); return@setOnClickListener }
            val m = RingLink.GestureMessage(gText.text?.toString().orEmpty(), gScroll.isChecked, gSpeed.value.toInt(), gReps.value.toInt())
            val n = RingLink.setGestureMessage(m)
            val all = RingProtocol.messageFrames(m.text, m.scroll).size
            toast(if (n == 0) "Built-in message restored" else "Sent: $n steps, %.1f s".format(n * m.msPerFrame * m.reps / 1000.0) +
                if (all >= RingProtocol.MSG_MAX_FRAMES) " (cut at ${RingProtocol.MSG_MAX_FRAMES} steps)" else "")
        }
        findViewById<MaterialButton>(R.id.gestTest).setOnClickListener {
            if (RingLink.state != RingLink.State.CONNECTED) toast("Not connected")
            else RingLink.send(RingProtocol.customGestureTest(), "gesture message test")
        }

        // Heart
        findViewById<SwitchMaterial>(R.id.heartSwitch).setOnCheckedChangeListener { _, on ->
            if (!updatingUi) RingLink.send(RingProtocol.customHeart(on), if (on) "live heartbeat on" else "live heartbeat off")
        }

        // Gestures
        listenSwitch.setOnCheckedChangeListener { _, on ->
            if (!updatingUi) RingLink.send(RingProtocol.cameraMode(on), if (on) "listen for taps" else "stop listening")
        }
        buildRuleRows()

        findViewById<MaterialButton>(R.id.resetButton).setOnClickListener { resetRing() }

        findViewById<MaterialButton>(R.id.devButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        if (RingLink.state == RingLink.State.DISCONNECTED) connectWithPermissions()
    }

    private val recLabelIds = mapOf(
        R.id.recTarget to "target", R.id.recBumps to "bumps_only", R.id.recFlick to "flick_only", R.id.recEveryday to "everyday", R.id.recShake to "shake",
    )
    private val recHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recUpdater = object : Runnable {
        override fun run() {
            val st = findViewById<TextView>(R.id.recStatus)
            val l = RingLink.recLabel
            if (l == null) return
            st.text = "Recording '$l': ${RingLink.recSamples} samples, %.0f samples/s, gaps ${RingLink.recGaps}".format(RingLink.recRate)
            recHandler.postDelayed(this, 500)
        }
    }

    private fun setupRecorder() {
        val btn = findViewById<MaterialButton>(R.id.recButton)
        val group = findViewById<android.widget.RadioGroup>(R.id.recLabels)
        btn.setOnClickListener {
            if (RingLink.recLabel == null) {
                val label = recLabelIds[group.checkedRadioButtonId] ?: "target"
                val err = RingLink.startRecording(label, findViewById<SwitchMaterial>(R.id.recFast).isChecked)
                if (err != null) { toast(err); return@setOnClickListener }
                btn.text = "Stop recording"
                recHandler.post(recUpdater)
            } else {
                val n = RingLink.recSamples; val gaps = RingLink.recGaps; val l = RingLink.recLabel
                RingLink.stopRecording()
                btn.text = "Start recording"
                findViewById<TextView>(R.id.recStatus).text = "Saved '$l': $n samples, gaps $gaps. Record another or tell Claude."
            }
        }
    }

    override fun onStart() { super.onStart(); RingLink.addListener(this) }
    override fun onStop() { RingLink.removeListener(this); super.onStop() }

    private fun connectWithPermissions() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) RingLink.connect() else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Put the ring back to stock behavior: no shutter mode, no HID mode, no measurements. */
    private fun resetRing() {
        if (RingLink.state != RingLink.State.CONNECTED) { toast("Not connected"); return }
        RingLink.send(RingProtocol.queryFactoryMode(), "reset: read factory mode")
        RingLink.send(RingProtocol.setFactoryMode(false), "reset: factory/self-test mode OFF")
        RingLink.send(RingProtocol.cameraMode(false), "reset: shutter mode off")
        RingLink.send(RingProtocol.setVideoHid(0, false), "reset: HID off, not default")
        for (sensor in listOf(3, 9, 10, 13, 8)) RingLink.send(RingProtocol.stopMeasurement(sensor), "reset: stop measurement $sensor")
        RingLink.send(RingProtocol.queryVideoHid(), "reset: read back mode")
        updatingUi = true; listenSwitch.isChecked = false; mouseToggle.check(R.id.mouseOff); updatingUi = false
        toast("Ring reset sent")
    }

    private fun sendTyped() {
        val t = messageInput.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        if (RingLink.state != RingLink.State.CONNECTED) { toast("Not connected"); return }
        RingLink.pushText(t)
        toast("Sent")
    }

    private fun renderSaved() {
        savedChips.removeAllViews()
        for (msg in GestureRules.savedMessages(this).reversed()) {
            savedChips.addView(Chip(this).apply {
                text = msg
                setOnClickListener {
                    if (RingLink.state == RingLink.State.CONNECTED) { RingLink.pushText(msg); toast("Sent") } else toast("Not connected")
                }
                setOnLongClickListener { GestureRules.removeMessage(this@HomeActivity, msg); renderSaved(); true }
            })
        }
    }

    private fun buildRuleRows() {
        val rows = findViewById<LinearLayout>(R.id.ruleRows)
        val choices = listOf("Nothing", "Show message", "Buzz")
        for (g in GestureRules.GESTURES) {
            val rule = GestureRules.get(this, g)
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this).apply {
                text = GestureRules.LABELS[g]; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, choices)
                setSelection(rule.type.ordinal)
            }
            top.addView(spinner)
            val textBox = TextInputLayout(this).apply {
                hint = "Message"
                addView(TextInputEditText(context).apply { setText(rule.text); isSingleLine = true })
                visibility = if (rule.type == GestureRules.Type.MESSAGE) View.VISIBLE else View.GONE
            }
            val edit = textBox.editText!!
            fun save() = GestureRules.set(
                this, g, GestureRules.Action(GestureRules.Type.values()[spinner.selectedItemPosition], edit.text.toString())
            )
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    textBox.visibility = if (pos == GestureRules.Type.MESSAGE.ordinal) View.VISIBLE else View.GONE
                    save()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            edit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = save()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            row.addView(top); row.addView(textBox)
            rows.addView(row)
        }
    }

    // ---- RingLink.Listener

    override fun onState(state: RingLink.State, detail: String) {
        statusText.text = detail
        val color = when (state) {
            RingLink.State.CONNECTED -> Color.parseColor("#2E7D32")
            RingLink.State.CONNECTING -> Color.parseColor("#F9A825")
            RingLink.State.DISCONNECTED -> Color.parseColor("#9E9E9E")
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        connectButton.text = if (state == RingLink.State.DISCONNECTED) "Connect" else "Disconnect"
    }

    override fun onEvent(event: RingProtocol.Event) {
        if (event !is RingProtocol.Event.Setting) return
        updatingUi = true
        Regex("Video HID mode=(\\d)").find(event.text)?.let { m ->
            modeButtons.entries.firstOrNull { it.value == m.groupValues[1].toInt() }?.let { mouseToggle.check(it.key) }
        }
        Regex("gesture=(on|off) fired=(\\d+) armed=(\\d+)").find(event.text)?.let { m ->
            findViewById<SwitchMaterial>(R.id.gestSwitch).isChecked = m.groupValues[1] == "on"
            findViewById<TextView>(R.id.gestStatus).text = "Detector ${m.groupValues[1]}. Fired ${m.groupValues[2]}x, armed ${m.groupValues[3]}x since ring boot."
        }
        Regex("Gesture (FIRED|armed) fired=(\\d+) armed=(\\d+)").find(event.text)?.let { m ->
            findViewById<TextView>(R.id.gestStatus).text =
                (if (m.groupValues[1] == "FIRED") "FIRED! " else "Armed (bumps seen)… ") + "Fired ${m.groupValues[2]}x, armed ${m.groupValues[3]}x since ring boot."
        }
        Regex("LED open=(\\w+) level=(\\d+)").find(event.text)?.let { m ->
            val lvl = if (m.groupValues[1] == "true") m.groupValues[2].toInt().coerceIn(0, 3) else 0
            brightness.value = lvl.toFloat()
        }
        updatingUi = false
    }

    override fun onGesture(name: String, action: GestureRules.Action) {
        lastGesture.text = "Last: ${GestureRules.LABELS[name] ?: name} → $action"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
