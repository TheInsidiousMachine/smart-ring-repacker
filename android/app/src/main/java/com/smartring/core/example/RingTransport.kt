package com.smartring.core.example

/**
 * Minimal BLE-ish transport so a ring driver can be unit-tested without Bluetooth.
 *
 * A real implementation wraps Android's BluetoothGatt (scan -> connectGatt -> discoverServices ->
 * enable notifications -> write characteristic). See testbed/RingLink.kt for the SY01 version and
 * docs/ADDING_A_RING.md for the checklist. Keep GATT queueing (one operation at a time) inside the
 * transport, not in the driver.
 */
interface RingTransport {
    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        /** One raw notification value, exactly as received (may be a fragment of a longer frame). */
        fun onNotify(data: ByteArray)
    }

    var listener: Listener?

    /** Start scanning/connecting; report via [Listener]. Real implementations retry until [disconnect]. */
    fun connect()
    fun disconnect()
    /** Write one packet to the ring's write characteristic. */
    fun write(data: ByteArray)
}

/** In-memory transport for tests: records writes, lets the test push notifications. */
class FakeTransport : RingTransport {
    override var listener: RingTransport.Listener? = null
    val written = mutableListOf<ByteArray>()
    var connectCalls = 0; private set

    override fun connect() { connectCalls++ }
    override fun disconnect() { listener?.onDisconnected() }
    override fun write(data: ByteArray) { written += data }

    fun simulateConnected(name: String = "EXAMPLE-RING") = listener?.onConnected(name)
    fun simulateNotify(data: ByteArray) = listener?.onNotify(data)
    fun simulateDisconnected() = listener?.onDisconnected()
}
