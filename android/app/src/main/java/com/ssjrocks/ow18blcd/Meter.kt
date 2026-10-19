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

/**
 * Finds the meter (advertised as "BDM"), connects, and passes on every reading. Reconnects when the
 * meter goes away (switched off, out of range) for as long as it's started. Callbacks run on the
 * main thread. Callers must hold the Bluetooth permissions before [start].
 */
@SuppressLint("MissingPermission")
class Meter(
    private val context: Context,
    private val onReading: (Protocol.Reading) -> Unit,
    private val onStatus: (String, Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("meter", Context.MODE_PRIVATE)
    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null

    fun start() {
        if (running) return
        running = true
        scan()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }

    private fun retry(status: String, delayMs: Long = 3000) {
        onStatus(status, false)
        main.removeCallbacksAndMessages(null)
        if (running) main.postDelayed({ scan() }, delayMs)
    }

    private fun scan() {
        if (!running) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return retry("Bluetooth is off")
        onStatus("Looking for the meter…", false)
        val filters = buildList {
            add(ScanFilter.Builder().setDeviceName(Protocol.NAME).build())
            prefs.getString("address", null)?.let { add(ScanFilter.Builder().setDeviceAddress(it).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
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
                if (!scanning || !running) return@post
                stopScan()
                main.removeCallbacksAndMessages(null)
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false; retry("Bluetooth scan failed (error $errorCode)") }
        }
    }

    private fun connect(device: BluetoothDevice) {
        onStatus("Connecting…", false)
        prefs.edit().putString("address", device.address).apply()
        AppReports.log("meter", "connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // Android sometimes never reports a failed connection; give up after a while.
        main.postDelayed({ if (gatt != null && !connected) { closeGatt(); retry("Couldn't connect") } }, 20_000)
    }

    @Volatile private var connected = false

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        connected = false
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
                val ch = g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.NOTIFY)
                if (ch == null) {
                    g.disconnect()
                    closeGatt()
                    return@post retry("That device isn't an OW18B (no meter service)")
                }
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(Protocol.CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
                connected = true
                main.removeCallbacksAndMessages(null)
                AppReports.log("meter", "connected to ${g.device.address}")
                onStatus("Connected", true)
            }
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

    private fun deliver(data: ByteArray) {
        val readings = Protocol.decodeAll(data)
        if (readings.isEmpty()) return
        main.post { readings.forEach(onReading) }
    }
}
