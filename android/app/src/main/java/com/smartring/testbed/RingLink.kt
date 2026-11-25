package com.smartring.testbed

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jieli.jl_bt_ota.impl.RcspAuth
import java.util.ArrayDeque
import java.util.UUID

/**
 * Single shared BLE link to the user's SY01, used by every screen.
 * All GATT operations go through a one-at-a-time queue (Android drops
 * overlapping writes). Never writes to ff00/ff01/ff02 (stock OTA control point).
 */
@SuppressLint("MissingPermission")
object RingLink {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onState(state: State, detail: String) {}
        fun onLog(line: String) {}
        fun onEvent(event: RingProtocol.Event) {}
        fun onGesture(name: String, action: GestureRules.Action) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private lateinit var appContext: Context

    var state = State.DISCONNECTED; private set
    var detail = "Not connected"; private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var otaWriteChar: BluetoothGattCharacteristic? = null
    private var pxiChar: BluetoothGattCharacteristic? = null   // ff01: stock OTA. Written ONLY by an approved PxiOtaSession.
    var ota: PxiOtaSession? = null; private set
    private var otaLastActivity = 0L
    private val assembler = RingProtocol.FrameAssembler()
    private val gestures = TapGestureClassifier()
    var session: SessionLog? = null; private set
    private var authSession: JlAuthSession? = null
    private val rcspAuth by lazy { RcspAuth() }

    private sealed class Op {
        /** noResponse: use write-without-response when the characteristic allows it (OTA data: the ring's
         *  per-chunk checksum ack already provides flow control and integrity). */
        data class WriteChar(val ch: BluetoothGattCharacteristic, val value: ByteArray, val label: String,
                             val noResponse: Boolean = false) : Op()
        data class EnableNotify(val ch: BluetoothGattCharacteristic) : Op()
        data class Mtu(val mtu: Int) : Op()
        data class Read(val ch: BluetoothGattCharacteristic) : Op()
    }
    private val ops = ArrayDeque<Op>()
    private var busy = false

    private val gesturePoll = object : Runnable {
        override fun run() {
            gestures.poll(SystemClock.uptimeMillis())?.let { onGesture(it) }
            main.postDelayed(this, 100)
        }
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        session = SessionLog.open(appContext.getExternalFilesDir(null))
        main.post(gesturePoll)
    }

    fun addListener(l: Listener) { listeners += l; l.onState(state, detail) }
    fun removeListener(l: Listener) { listeners -= l }

    fun log(msg: String) {
        android.util.Log.i("SmartRing", msg)
        session?.note(msg)
        main.post { listeners.forEach { it.onLog(msg) } }
    }

    private fun setState(s: State, d: String) {
        state = s; detail = d
        main.post { listeners.forEach { it.onState(s, d) } }
    }

    // ---------------------------------------------------------------- connect

    /** True from [connect] until the user calls [disconnect]: any other drop (ring reboot after a
     *  firmware update, out of range) reconnects by itself. */
    @Volatile private var wantConnected = false
    private var lastDevice: BluetoothDevice? = null

    fun connect() {
        wantConnected = true
        if (state != State.DISCONNECTED) return
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { setState(State.DISCONNECTED, "Bluetooth is off"); return }
        setState(State.CONNECTING, "Looking for SY01…")
        adapter.bondedDevices.firstOrNull { it.name?.contains("SY01", true) == true }?.let {
            log("Found bonded ${it.name} / ${it.address}")
            open(it); return
        }
        val scanner = adapter.bluetoothLeScanner
        var found = false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (!found && name?.contains("SY01", true) == true) {
                    found = true; scanner.stopScan(this); open(result.device)
                }
            }
        }
        scanner.startScan(cb)
        main.postDelayed({
            if (!found) {
                scanner.stopScan(cb); setState(State.DISCONNECTED, "Ring not found — is RWFit holding it?")
                if (wantConnected) main.postDelayed({ if (wantConnected) connect() }, 15_000)
            }
        }, 10_000)
    }

    private fun open(device: BluetoothDevice) {
        lastDevice = device
        setState(State.CONNECTING, "Connecting to ${device.name}…")
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** User disconnect: stays disconnected until [connect]. */
    fun disconnect() {
        wantConnected = false
        teardown()
        setState(State.DISCONNECTED, "Not connected")
    }

    private fun teardown() {
        gatt?.disconnect(); gatt?.close(); gatt = null
        writeChar = null; otaWriteChar = null
        ops.clear(); busy = false; assembler.reset()
    }

    /** Unexpected drop: wait for the ring with an Android auto-connect (no polling, no battery cost). */
    private fun dropped(status: Int) {
        log("Disconnected (status=$status)")
        teardown()
        val dev = lastDevice
        if (!wantConnected || dev == null) { setState(State.DISCONNECTED, "Not connected"); return }
        setState(State.CONNECTING, "Reconnecting…")
        main.postDelayed({
            if (wantConnected && gatt == null) {
                log("Reconnecting to ${dev.address} (auto-connect)")
                gatt = dev.connectGatt(appContext, true, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }, 1_500)
    }

    // ---------------------------------------------------------------- send

    fun send(packet: ByteArray, label: String) {
        val ch = writeChar ?: return log("Not connected — can't send \"$label\"")
        enqueue(Op.WriteChar(ch, packet, label))
    }

    // ------------------------------------------------ gesture message (custom firmware)
    private fun msgPrefs() = appContext.getSharedPreferences("gesture_message", Context.MODE_PRIVATE)
    data class GestureMessage(val text: String, val scroll: Boolean, val msPerFrame: Int, val reps: Int)
    fun savedMessage(): GestureMessage? = savedMessageFrom(appContext)
    fun savedMessageFrom(c: Context): GestureMessage? {
        val p = c.getSharedPreferences("gesture_message", Context.MODE_PRIVATE); val t = p.getString("text", null) ?: return null
        return GestureMessage(t, p.getBoolean("scroll", false), p.getInt("ms", 500), p.getInt("reps", 2))
    }
    /** Save on the phone and send to the ring. The ring keeps it in RAM only, so it is re-sent on every connect.
     *  Blank text = the built-in message. Returns the frame count. */
    fun setGestureMessage(m: GestureMessage): Int {
        msgPrefs().edit().putString("text", m.text).putBoolean("scroll", m.scroll)
            .putInt("ms", m.msPerFrame).putInt("reps", m.reps).apply()
        return sendGestureMessage(m)
    }
    private fun sendGestureMessage(m: GestureMessage): Int {
        val frames = RingProtocol.messageFrames(m.text, m.scroll)
        frames.chunked(2).forEachIndexed { i, pair -> send(RingProtocol.customMessageFrames(i * 2, pair), "message frames ${i * 2}+") }
        send(RingProtocol.customMessageConfig(frames.size, m.msPerFrame, m.reps),
            "gesture message: ${frames.size} frames, ${m.msPerFrame} ms, x${m.reps}")
        return frames.size
    }

    private var scroll: Runnable? = null

    /** Show text on the ring screen (custom firmware). Longer than 5 characters scrolls,
     *  driven from the phone: one 5-character window every 350 ms. */
    fun pushText(text: String) {
        scroll?.let { main.removeCallbacks(it) }; scroll = null
        val t = text.trim().uppercase()
        if (t.length <= 5) { send(RingProtocol.customText(t), "screen: \"$t\""); return }
        val padded = "    $t     "
        var i = 0
        log("screen (scrolling): \"$t\"")
        val r = object : Runnable {
            override fun run() {
                if (i + 5 > padded.length || writeChar == null) { scroll = null; return }
                val ch = writeChar ?: return
                enqueue(Op.WriteChar(ch, RingProtocol.customText(padded.substring(i, i + 5)), "scroll"))
                i++
                main.postDelayed(this, 350)
            }
        }
        scroll = r; main.post(r)
    }

    // ---------------------------------------------------------------- motion recorder

    /** Saves raw accelerometer samples from our firmware to files/recordings/<label>_<time>.csv */
    var recLabel: String? = null; private set
    var recSamples = 0; private set
    private var recWriter: java.io.Writer? = null
    private var recLastSeq = -1
    var recGaps = 0; private set
    var recFile: String? = null; private set

    /** samples/s over the last ~2 s, from phone arrival times */
    var recRate = 0.0; private set
    private val recTimes = ArrayDeque<Pair<Long, Int>>()

    fun startRecording(label: String, fast: Boolean = true): String? {
        if (writeChar == null) return "Not connected"
        stopRecording()
        val dir = java.io.File(appContext.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = java.io.File(dir, "${label}${if (fast) "_fast" else ""}_$ts.csv")
        recWriter = f.bufferedWriter().apply { write("phone_ms,seq,x,y,z\n") }
        recFile = f.path
        recLabel = label; recSamples = 0; recGaps = 0; recLastSeq = -1; recRate = 0.0; recTimes.clear()
        send(RingProtocol.customRecord(true, if (fast) 6 else 4), "recorder on ($label${if (fast) ", fast" else ""})")
        log("Recording to ${f.path}")
        return null
    }

    fun stopRecording() {
        val w = recWriter ?: return
        recWriter = null
        if (writeChar != null) send(RingProtocol.customRecord(false), "recorder off")
        w.close()
        log("Recording '${recLabel}' saved: $recSamples samples, $recGaps gaps")
        recLabel = null
    }

    private fun onMotion(ev: RingProtocol.Event.Motion) {
        val w = recWriter ?: return
        if (recLastSeq >= 0 && ((recLastSeq + 1) and 0xFF) != ev.seq) recGaps++
        recLastSeq = ev.seq
        val now = SystemClock.uptimeMillis()
        for (s in ev.samples) w.write("$now,${ev.seq},${s[0]},${s[1]},${s[2]}\n")
        recSamples += ev.samples.size
        recTimes.addLast(now to ev.samples.size)
        while (recTimes.size > 1 && now - recTimes.first().first > 2000) recTimes.removeFirst()
        val span = (now - recTimes.first().first).coerceAtLeast(1)
        if (span > 500) recRate = (recTimes.sumOf { it.second } - recTimes.first().second) * 1000.0 / span
    }

    private fun enqueue(op: Op) { main.post { ops.add(op); pump() } }

    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        val op = ops.poll() ?: return
        busy = true
        val ok = when (op) {
            is Op.WriteChar -> {
                if (op.label != "ota-data" && op.label != "scroll") log("-> ${op.label}: ${RingProtocol.toHex(op.value)}")
                session?.raw("TX", op.ch.uuid.toString().substring(4, 8), op.value)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(op.ch, op.value, writeType(op.ch, op.noResponse)) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        op.ch.value = op.value
                        op.ch.writeType = writeType(op.ch, op.noResponse)
                        g.writeCharacteristic(op.ch)
                    }
                }
            }
            is Op.EnableNotify -> {
                g.setCharacteristicNotification(op.ch, true)
                val cccd = op.ch.getDescriptor(RingProtocol.CCCD_UUID)
                if (cccd == null) false
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }
            is Op.Mtu -> g.requestMtu(op.mtu)
            is Op.Read -> g.readCharacteristic(op.ch)
        }
        if (!ok) { log("GATT op failed to start: $op"); busy = false; main.post { pump() } }
    }

    private fun writeType(ch: BluetoothGattCharacteristic, preferNoResponse: Boolean = false) = when {
        preferNoResponse && ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }

    private fun opDone() = main.post { busy = false; pump() }

    // ---------------------------------------------------------------- GATT callback

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setState(State.CONNECTING, "Discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { if (gatt === g || gatt == null) dropped(status) else g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            for (svc in g.services) {
                log("Service ${svc.uuid}")
                svc.characteristics.forEach { log("  char ${it.uuid} props=0x%02x".format(it.properties)) }
            }
            val data = g.getService(RingProtocol.SERVICE_UUID)
            writeChar = data?.getCharacteristic(RingProtocol.WRITE_CHAR_UUID)
            val jl = g.getService(RingProtocol.OTA_SERVICE_UUID)
            val pxi = g.getService(UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb"))
            otaWriteChar = jl?.getCharacteristic(RingProtocol.OTA_WRITE_CHAR_UUID)
            pxiChar = pxi?.getCharacteristic(UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"))
            log("Chip-path check: JieLi ae00=${jl != null}, PixArt-OTA ff00=${pxi != null}")
            listOfNotNull(
                data?.getCharacteristic(RingProtocol.NOTIFY_CHAR_UUID),
                jl?.getCharacteristic(RingProtocol.OTA_NOTIFY_CHAR_UUID),
                pxi?.getCharacteristic(UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")), // listen only
            ).forEach { enqueue(Op.EnableNotify(it)) }
            enqueue(Op.Mtu(185))
            if (writeChar == null) setState(State.CONNECTING, "Connected, but no a00a data service")
            else {
                setState(State.CONNECTED, "Connected: ${g.device.name ?: g.device.address}")
                send(RingProtocol.queryVideoHid(), "read mouse mode")
                send(RingProtocol.queryLedLevel(), "read brightness")
                send(RingProtocol.queryFirmwareVersion(), "read firmware version")
                send(RingProtocol.queryBattery(), "read battery")
                send(RingProtocol.queryDeviceSupport(), "read feature list")
                savedMessage()?.let { sendGestureMessage(it) }
                send(RingProtocol.setTime(), "sync clock")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Notify enable failed on ${d.characteristic.uuid} ($status)")
            opDone()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log("MTU $mtu (status=$status)"); opDone() }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Write FAILED on ${c.uuid} ($status)")
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            incoming(c.uuid, value)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(c.uuid, value, status); opDone()
        }

        @Deprecated("pre-33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onRead(c.uuid, c.value ?: ByteArray(0), status)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) opDone()
        }

        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) incoming(c.uuid, c.value)
        }
    }

    // ---------------------------------------------------------------- incoming

    private fun incoming(uuid: UUID, value: ByteArray) {
        val isMotion = value.size > 8 && value[0] == 0xAB.toByte() && value[6] == 0x7A.toByte() && value[7] == 0x11.toByte()
        if (!isMotion) session?.raw("RX", uuid.toString().substring(4, 8), value)   // motion goes to its own CSV
        if (uuid == RingProtocol.NOTIFY_CHAR_UUID) {
            val frame = assembler.feed(value) ?: return
            val ev = RingProtocol.classify(frame)
            val crc = if (frame.crcOk) "" else " [CRC MISMATCH]"
            when (ev) {
                is RingProtocol.Event.ShutterTap, is RingProtocol.Event.Count -> {
                    log("<- TAP $ev")
                    main.post { gestures.tap(SystemClock.uptimeMillis())?.let { onGesture(it) } }
                }
                is RingProtocol.Event.Music -> media(ev.value)
                is RingProtocol.Event.Motion -> { main.post { onMotion(ev) }; return }
                is RingProtocol.Event.Ppg -> {}
                is RingProtocol.Event.Unknown -> log("<- ${ev.text} flag=0x%02x$crc".format(frame.protocolFlag))
                else -> log("<- $ev$crc")
            }
            main.post { listeners.forEach { it.onEvent(ev) } }
            return
        }
        if (uuid == PXI_UUID) {
            log("<- [ff01] ${RingProtocol.toHex(value)}")
            ota?.let { otaLastActivity = SystemClock.uptimeMillis(); main.post { it.onNotify(value); otaTick() } }
            return
        }
        if (uuid == RingProtocol.OTA_NOTIFY_CHAR_UUID && authSession?.onData(value) == true) {
            log("<- [ae02] auth → ${authSession!!.state}${authSession!!.failure?.let { " ($it)" } ?: ""}")
            return
        }
        log("<- [${uuid.toString().substring(4, 8)}] ${RingProtocol.toHex(value)}")
    }

    private var lastMediaAt = 0L

    /** Same mapping and 1.1 s debounce as the stock app (service/u.java + h0.java). */
    private fun media(v: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMediaAt < 1100) return
        lastMediaAt = now
        val am = appContext.getSystemService(android.media.AudioManager::class.java) ?: return
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        when (v) {
            2 -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            3 -> key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            4 -> key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            5 -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
            6 -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
            else -> { log("Music value $v not mapped"); return }
        }
        log("Music control $v")
    }

    private fun onGesture(name: String) {
        val action = GestureRules.get(appContext, name)
        log("Gesture: $name → $action")
        when (action.type) {
            GestureRules.Type.MESSAGE -> if (action.text.isNotBlank()) pushText(action.text)
            GestureRules.Type.BUZZ -> send(RingProtocol.findDevice(), "gesture buzz")
            GestureRules.Type.NONE -> {}
        }
        main.post { listeners.forEach { it.onGesture(name, action) } }
    }

    // ---------------------------------------------------------------- stock OTA (gated)

    private val PXI_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    private fun onRead(uuid: UUID, value: ByteArray, status: Int) {
        session?.raw("RD", uuid.toString().substring(4, 8), value)
        log("<- read [${uuid.toString().substring(4, 8)}] status=$status ${RingProtocol.toHex(value)}")
        if (uuid == PXI_UUID) ota?.let { otaLastActivity = SystemClock.uptimeMillis(); main.post { it.onRead(value); otaTick() } }
    }

    /**
     * Send a firmware image with the stock OTA protocol. Caller (UI) must already have
     * obtained explicit user confirmation for THIS hash. Refuses anything not allowlisted.
     */
    fun startFirmwareUpdate(image: ByteArray): String? {
        val hash = PxiOtaSession.sha256(image)
        if (hash !in PxiOtaSession.ALLOWED_SHA256) return "Refused: image hash $hash is not allowlisted"
        val ch = pxiChar ?: return "Refused: ring has no ff00/ff01 OTA service"
        if (state != State.CONNECTED) return "Refused: not connected"
        if (ota != null && ota!!.state !in setOf(PxiOtaSession.State.DONE, PxiOtaSession.State.FAILED)) return "Refused: update already running"
        log("OTA START ${PxiOtaSession.ALLOWED_SHA256[hash]} sha256=$hash size=${image.size}")
        val s = PxiOtaSession(image, object : PxiOtaSession.Io {
            override fun writeCommand(bytes: ByteArray) = enqueue(Op.WriteChar(ch, bytes, "ota-cmd"))
            override fun writeData(bytes: ByteArray) = enqueue(Op.WriteChar(ch, bytes, "ota-data", noResponse = true))
            override fun requestRead(delayMs: Long) { main.postDelayed({ enqueue(Op.Read(ch)) }, delayMs) }
            override fun log(msg: String) = this@RingLink.log(msg)
        })
        ota = s
        otaLastActivity = SystemClock.uptimeMillis()
        otaStartMs = SystemClock.uptimeMillis(); otaTimed = false
        // Like the stock app: short connection interval while transferring (back to balanced when done).
        val hi = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        log("OTA: connection priority HIGH requested ($hi), data as write-without-response")
        s.start(maxChunk = 182)
        main.postDelayed(otaWatchdog, 2000)
        return null
    }

    private var lastLoggedPct = -1
    private var otaStartMs = 0L
    private var otaTimed = false
    private fun otaTick() {
        val s = ota ?: return
        if (!otaTimed && s.state in setOf(PxiOtaSession.State.WAIT_UPGRADE_ACK, PxiOtaSession.State.DONE, PxiOtaSession.State.FAILED)) {
            otaTimed = true
            log("OTA transfer time %.1f s (%s)".format((SystemClock.uptimeMillis() - otaStartMs) / 1000.0, s.state))
            if (s.state == PxiOtaSession.State.FAILED) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        }
        val pct = (s.progress * 100).toInt()
        if (pct / 5 != lastLoggedPct / 5) { lastLoggedPct = pct; log("OTA ${s.state} $pct%") }
    }

    private val otaWatchdog = object : Runnable {
        override fun run() {
            val s = ota ?: return
            if (s.state == PxiOtaSession.State.DONE || s.state == PxiOtaSession.State.FAILED) return
            if (SystemClock.uptimeMillis() - otaLastActivity > 10_000) { s.timeout(); return }
            main.postDelayed(this, 2000)
        }
    }

    // ---------------------------------------------------------------- dev: auth compatibility check

    fun startOtaAuthTest() {
        val ch = otaWriteChar ?: return log("No JieLi ae00/ae01 service — handshake not applicable")
        try {
            val s = JlAuthSession({ rcspAuth.getRandomAuthData() }, { rcspAuth.getEncryptedAuthData(it) }) {
                enqueue(Op.WriteChar(ch, it, "auth ${it.size}B"))
            }
            authSession = s
            s.start()
            main.postDelayed({ s.timeout(); s.failure?.let { log("Auth: $it") } }, 5000)
        } catch (t: Throwable) {
            log("RcspAuth error: ${t.message}")
        }
    }
}
