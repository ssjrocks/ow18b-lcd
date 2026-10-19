package com.ssjrocks.ow18blcd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ssjrocks.appreports.AppReports
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.UUID

/**
 * Finds the meter (advertised as "BDM" unless renamed), connects, passes on every reading, and runs
 * commands (keys, info, rename, offline logs). Reconnects when the meter goes away for as long as
 * it's started and not paused. Everything, callbacks included, runs on the main thread. Callers
 * must hold the Bluetooth permissions before [start].
 */
@SuppressLint("MissingPermission")
class Meter(
    private val context: Context,
    private val onReading: (Protocol.Reading) -> Unit,
    private val onStatus: (String, Boolean) -> Unit,
    private val onInfo: (Protocol.DeviceInfo) -> Unit,
) {
    class Found(val address: String, val name: String, val rssi: Int)
    class NotConnected : Exception("The meter isn't connected")

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("meter", Context.MODE_PRIVATE)
    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var connected = false
    var paused = false
        private set
    var info: Protocol.DeviceInfo? = null
        private set
    var address: String? = prefs.getString("address", null)
        private set
    val name: String get() = prefs.getString("name:$address", null) ?: Protocol.NAME

    fun start() {
        if (running) return
        running = true
        if (!paused) scan()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        failAll(NotConnected())
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        connected = false
    }

    /** Disconnect and stay disconnected (true), or reconnect (false). */
    fun setPaused(p: Boolean) {
        paused = p
        if (p) {
            main.removeCallbacksAndMessages(null)
            stopScan()
            failAll(NotConnected())
            gatt?.let { it.disconnect(); it.close() }
            gatt = null
            connected = false
            onStatus("Disconnected", false)
        } else if (running) scan()
    }

    /** Switch to another meter. */
    fun useAddress(a: String) {
        address = a
        prefs.edit().putString("address", a).apply()
        val wasRunning = running
        stop()
        paused = false
        if (wasRunning) start()
    }

    private fun retry(status: String, delayMs: Long = 3000) {
        onStatus(status, false)
        main.removeCallbacksAndMessages(null)
        if (running && !paused) main.postDelayed({ scan() }, delayMs)
    }

    // --- finding and connecting ---

    private fun scan() {
        if (!running || paused || gatt != null) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return retry("Bluetooth is off")
        onStatus("Looking for the meter…", false)
        val filter = address?.let { ScanFilter.Builder().setDeviceAddress(it).build() }
            ?: ScanFilter.Builder().setDeviceName(Protocol.NAME).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (e: Exception) {
            return retry("Can't scan: ${e.message}")
        }
        main.postDelayed({
            if (scanning) {
                stopScan()
                retry("Meter not found. Is it on, with Bluetooth enabled?", 1000)
            }
        }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning || !running || paused) return@post
                stopScan()
                main.removeCallbacksAndMessages(null)
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false; retry("Bluetooth scan failed (error $errorCode)") }
        }
    }

    /** Nearby named devices for the meter picker, meters first. */
    fun scanDevices(seconds: Int, done: (List<Found>) -> Unit) {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return done(emptyList())
        val found = LinkedHashMap<String, Found>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
                main.post { found[result.device.address] = Found(result.device.address, n, result.rssi) }
            }
        }
        runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
        main.postDelayed({
            runCatching { scanner.stopScan(cb) }
            if (connected && address != null && address !in found) found[address!!] = Found(address!!, name, 0)
            val known = prefs.getStringSet("known", emptySet()) ?: emptySet()
            done(found.values.sortedWith(compareBy<Found>({ it.name != Protocol.NAME && it.address !in known }, { -it.rssi })))
        }, seconds * 1000L)
    }

    private fun connect(device: BluetoothDevice) {
        onStatus("Connecting…", false)
        address = device.address
        prefs.edit().putString("address", device.address)
            .putStringSet("known", (prefs.getStringSet("known", emptySet()) ?: emptySet()) + device.address).apply()
        AppReports.log("meter", "connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // Android sometimes never reports a failed connection; give up after a while.
        main.postDelayed({ if (gatt != null && !connected) { closeGatt(); retry("Couldn't connect") } }, 20_000)
    }

    private fun closeGatt() {
        failAll(NotConnected())
        gatt?.close()
        gatt = null
        connected = false
    }

    // --- one GATT operation at a time ---

    private sealed class Op(val done: (ByteArray?, Throwable?) -> Unit) {
        class Write(val char: UUID, val data: ByteArray, done: (ByteArray?, Throwable?) -> Unit) : Op(done)
        class Read(val char: UUID, done: (ByteArray?, Throwable?) -> Unit) : Op(done)
        class Subscribe(done: (ByteArray?, Throwable?) -> Unit) : Op(done)
    }

    private val ops = ArrayDeque<Op>()
    private var current: Op? = null
    private val opTimeout = Runnable { finish(null, IllegalStateException("The meter didn't answer")) }

    private fun enqueue(op: Op) {
        if (gatt == null) return op.done(null, NotConnected())
        ops.addLast(op)
        if (current == null) next()
    }

    private fun next() {
        val g = gatt
        val op = ops.removeFirstOrNull() ?: return
        current = op
        main.postDelayed(opTimeout, 5000)
        val service = g?.getService(Protocol.SERVICE)
        val ok = when (op) {
            is Op.Write -> service?.getCharacteristic(op.char)?.let { ch ->
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ch, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = op.data
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            }
            is Op.Read -> service?.getCharacteristic(op.char)?.let { g.readCharacteristic(it) }
            is Op.Subscribe -> service?.getCharacteristic(Protocol.NOTIFY)?.let { ch ->
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(Protocol.CCCD)
                when {
                    cccd == null -> { main.post { finish(null, null) }; true } // notifies without it
                    Build.VERSION.SDK_INT >= 33 ->
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                    else -> {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }
        } ?: false
        if (!ok) finish(null, IllegalStateException("Couldn't send to the meter"))
    }

    private fun finish(value: ByteArray?, error: Throwable?) {
        val op = current ?: return
        main.removeCallbacks(opTimeout)
        current = null
        op.done(value, error)
        next()
    }

    private fun failAll(e: Throwable) {
        main.removeCallbacks(opTimeout)
        val pending = listOfNotNull(current) + ops
        current = null
        ops.clear()
        pending.forEach { it.done(null, e) }
        dumpDone?.invoke(null, e)
        dump = null
        dumpDone = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = connected
                    closeGatt()
                    AppReports.log("meter", "disconnected (status $status)")
                    retry(if (was) "Disconnected" else "Couldn't connect (status $status)", 1000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.NOTIFY) == null) {
                    g.disconnect()
                    closeGatt()
                    return@post retry("That device isn't an OW18B (no meter service)")
                }
                enqueue(Op.Subscribe { _, _ -> })
                enqueue(Op.Read(Protocol.INFO) { v, _ ->
                    info = v?.let { Protocol.parseInfo(it) }
                    connected = true
                    main.removeCallbacksAndMessages(null)
                    AppReports.log("meter", "connected to ${g.device.address}, ${info?.model} fw ${info?.firmware}")
                    onStatus("Connected to $name", true)
                    info?.let(onInfo)
                })
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post { finish(null, if (status == BluetoothGatt.GATT_SUCCESS) null else IllegalStateException("The meter refused it (status $status)")) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { finish(value, if (status == BluetoothGatt.GATT_SUCCESS) null else IllegalStateException("Read failed (status $status)")) }
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) {
                val v = ch.value?.copyOf()
                main.post { finish(v, if (status == BluetoothGatt.GATT_SUCCESS) null else IllegalStateException("Read failed (status $status)")) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { finish(null, null) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) ch.value?.let { deliver(it.copyOf()) }
        }
    }

    // --- readings and log dumps ---

    private var dump: ByteArrayOutputStream? = null
    private var dumpDone: ((ByteArray?, Throwable?) -> Unit)? = null

    private fun deliver(data: ByteArray) {
        main.post {
            val d = dump
            if (d != null) {
                d.write(data)
                if (Protocol.dumpComplete(d.toByteArray())) {
                    val cb = dumpDone
                    dump = null
                    dumpDone = null
                    cb?.invoke(d.toByteArray(), null)
                }
                return@post
            }
            Protocol.decodeAll(data).forEach(onReading)
        }
    }

    // --- commands ---

    fun press(key: Int, press: Int = Protocol.SHORT, done: (Throwable?) -> Unit = {}) =
        enqueue(Op.Write(Protocol.KEYS, Protocol.key(key, press)) { _, e -> done(e) })

    fun readInfo(done: (Protocol.DeviceInfo?, Throwable?) -> Unit) =
        enqueue(Op.Read(Protocol.INFO) { v, e ->
            if (v != null) info = Protocol.parseInfo(v).also(onInfo)
            done(info, e)
        })

    fun rename(newName: String, done: (Throwable?) -> Unit) {
        val data = try { Protocol.rename(newName) } catch (e: IllegalArgumentException) { return done(e) }
        enqueue(Op.Write(Protocol.COMMAND, data) { _, e ->
            if (e == null) prefs.edit().putString("name:$address", newName).apply()
            done(e)
        })
    }

    /** Sets the meter's clock and starts an offline log; the meter then turns Bluetooth off. */
    fun startRecording(interval: Int, count: Int, done: (Throwable?) -> Unit) {
        val data = try { Protocol.startRecord(interval, count) } catch (e: IllegalArgumentException) { return done(e) }
        enqueue(Op.Write(Protocol.COMMAND, Protocol.setClock(LocalDateTime.now())) { _, e ->
            if (e != null) return@Write done(e)
        })
        enqueue(Op.Write(Protocol.COMMAND, data) { _, e -> done(e) })
    }

    fun stopRecording(done: (Throwable?) -> Unit) {
        enqueue(Op.Write(Protocol.COMMAND, Protocol.STOP_RECORD) { _, e ->
            if (e != null) return@Write done(e)
            main.postDelayed({ readInfo { _, e2 -> done(e2) } }, 500)
        })
    }

    /** Reads the offline log from the meter's memory. [progress] gets the bytes received so far.
     *  A dump once came without its readings, so a short one is read again (up to 3 times). */
    fun readLog(progress: (Int) -> Unit, done: (Protocol.OfflineLog?, Throwable?) -> Unit, attempt: Int = 1) {
        readDump(progress) { log, e ->
            when {
                log == null -> done(null, e)
                log.complete -> done(log, null)
                attempt < 3 -> main.postDelayed({ readLog(progress, done, attempt + 1) }, 1000)
                else -> done(null, IllegalStateException("The log came incomplete (${log.received} of ${log.expected} bytes); try again"))
            }
        }
    }

    private fun readDump(progress: (Int) -> Unit, done: (Protocol.OfflineLog?, Throwable?) -> Unit) {
        if (gatt == null || !connected) return done(null, NotConnected())
        val dated = (info?.firmwareNumber ?: 12) >= 12
        val buffer = ByteArrayOutputStream()
        var last = -1
        val watchdog = object : Runnable {
            override fun run() {
                if (dump !== buffer) return
                if (buffer.size() == last) {
                    dump = null
                    dumpDone = null
                    done(null, IllegalStateException("The meter stopped sending its log"))
                } else {
                    last = buffer.size()
                    progress(last)
                    main.postDelayed(this, 5000)
                }
            }
        }
        dump = buffer
        dumpDone = { bytes, e ->
            main.removeCallbacks(watchdog)
            if (bytes == null) done(null, e)
            else try { done(Protocol.parseDump(bytes, dated), null) } catch (x: Exception) { done(null, x) }
        }
        enqueue(Op.Write(Protocol.COMMAND, if (dated) Protocol.READ_LOG else Protocol.READ_LOG_OLD) { _, e ->
            if (e != null) {
                dump = null
                dumpDone = null
                done(null, e)
            }
        })
        main.postDelayed(watchdog, 5000)
    }
}
