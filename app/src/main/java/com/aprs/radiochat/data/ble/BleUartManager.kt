/*
 * RadioChat APRS — Android APRS client for KISS BLE radios,
 * DireWolf (KISS TCP) and APRS-IS.
 * Copyright (C) 2026 Luis Almonte (HI3LAG)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aprs.radiochat.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aprs.radiochat.data.kiss.KissCodec
import com.aprs.radiochat.data.model.BleConnectionState
import com.aprs.radiochat.data.model.BleDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * BLE KISS RT-950 Pro — RX notify FFE1, TX write FF31 (KISS BLE).
 */
@SuppressLint("MissingPermission")
class BleUartManager(context: Context) {

    private data class TxChannel(
        val characteristic: BluetoothGattCharacteristic,
        val writeType: Int,
        val label: String
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _connectionState =
        MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _lastTxError = MutableStateFlow<String?>(null)
    val lastTxError: StateFlow<String?> = _lastTxError.asStateFlow()

    private val _kissPayloads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val kissPayloads: SharedFlow<ByteArray> = _kissPayloads.asSharedFlow()

    private val kissDecoder = KissCodec.Decoder()
    private val gattMutex = Mutex()
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private var txChannels = listOf<TxChannel>()
    @Volatile private var maxWritePayload: Int = 20
    @Volatile private var mtuReady = false

    private var scanJob: Job? = null
    private var writeJob: Job? = null
    private var mtuFallbackJob: Job? = null
    @Volatile private var writeAck: CompletableDeferred<Boolean>? = null

    private val knownDevices = mutableMapOf<String, BluetoothDevice>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            maybeOfferDevice(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { maybeOfferDevice(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.Error("BLE scan failed ($errorCode)")
        }
    }

    private fun maybeOfferDevice(result: ScanResult) {
        val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
        val hasKissService = result.scanRecord?.serviceUuids
            ?.any { it.uuid == BleUartProfile.SERVICE_UUID } == true
        val nameMatches = name.isNotBlank() &&
            BleUartProfile.DEVICE_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        if (!nameMatches && !hasKissService) return

        val address = result.device.address
        knownDevices[address] = result.device
        _discoveredDevices.update { current ->
            (current.filterNot { it.address == address } +
                BleDeviceInfo(name.ifBlank { "RT-950 Pro" }, address, result.rssi))
                .sortedByDescending { it.rssi }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("GATT status=$status")
                cleanupGatt()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connecting(
                        g.device.name ?: g.device.address
                    )
                    maxWritePayload = 20
                    mtuReady = false
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.requestMtu(517)
                    mtuFallbackJob?.cancel()
                    mtuFallbackJob = scope.launch {
                        delay(1_200)
                        if (!mtuReady && gatt === g) {
                            Log.w(TAG, "MTU timeout → discoverServices (chunk=20)")
                            g.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.Disconnected
                    cleanupGatt()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                maxWritePayload = (mtu - 3).coerceIn(20, 512)
                mtuReady = true
            }
            Log.i(TAG, "MTU=$mtu chunk=$maxWritePayload ready=$mtuReady")
            mtuFallbackJob?.cancel()
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("discoverServices=$status")
                return
            }

            val notifyChar = findCharacteristic(g, BleUartProfile.NOTIFY_CHAR_UUID)
            if (notifyChar == null) {
                _connectionState.value = BleConnectionState.Error("FFE1 (notify) not found")
                logServices(g)
                return
            }

            txChannels = buildTxChannels(g)
            if (txChannels.isEmpty()) {
                _connectionState.value = BleConnectionState.Error("No writable TX channel")
                logServices(g)
                return
            }

            Log.i(TAG, "TX candidates: ${txChannels.joinToString { it.label }}")
            enableNotifications(g, notifyChar)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                descriptor.uuid == BleUartProfile.CCCD_UUID
            ) {
                val name = g.device.name ?: "RT-950 Pro"
                _connectionState.value = BleConnectionState.Connected(name, g.device.address)
                startWriteLoop()
                Log.i(TAG, "Connected RX=FFE1 | TX=${txChannels.joinToString { it.label }}")
            } else {
                _connectionState.value =
                    BleConnectionState.Error("Notifications were not enabled ($status)")
            }
        }

        @Deprecated("Deprecated in API 33+")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BleUartProfile.NOTIFY_CHAR_UUID) {
                @Suppress("DEPRECATION")
                handleIncoming(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleUartProfile.NOTIFY_CHAR_UUID) {
                handleIncoming(value)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** BLE KISS mode: FF31 only (FFE1 is notify + CPS, it does not key RF TX in KISS). */
    private fun buildTxChannels(g: BluetoothGatt): List<TxChannel> {
        val ff31 = findWritable(g, BleUartProfile.WRITE_CHAR_UUID)
        if (ff31 != null) {
            return listOf(makeTxChannel(ff31, "FF31"))
        }
        Log.w(TAG, "FF31 unavailable; trying FFE2/FFE1 as a fallback")
        return listOfNotNull(
            findWritable(g, BleUartProfile.WRITE_CHAR_ALT_UUID)?.let { makeTxChannel(it, "FFE2") },
            findWritable(g, BleUartProfile.NOTIFY_CHAR_UUID)?.let { makeTxChannel(it, "FFE1") }
        )
    }

    private fun makeTxChannel(c: BluetoothGattCharacteristic, label: String): TxChannel {
        val props = c.properties
        // FF31 / BLE-KISS: write WITH response confirms the TNC got the frame;
        // write-without-response only queues on Android and can report "OK" with no RF.
        val type = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        Log.i(TAG, "$label props=0x${props.toString(16)} writeType=$type")
        return TxChannel(c, type, label)
    }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in g.services.orEmpty()) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun findWritable(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        val c = findCharacteristic(g, uuid) ?: return null
        val writable = c.properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0
        return if (writable) c else null
    }

    private fun logServices(g: BluetoothGatt) {
        for (service in g.services.orEmpty()) {
            Log.w(TAG, "Service ${service.uuid}")
            for (c in service.characteristics) {
                Log.w(TAG, "  ${c.uuid} props=${c.properties}")
            }
        }
    }

    fun startScan(timeoutMs: Long = 20_000L) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is off")
            return
        }
        stopScan()
        disconnect()
        knownDevices.clear()
        _discoveredDevices.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning

        bt.bluetoothLeScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )

        scanJob = scope.launch {
            delay(timeoutMs)
            if (_connectionState.value is BleConnectionState.Scanning) {
                stopScan()
                _connectionState.value = if (_discoveredDevices.value.isEmpty()) {
                    BleConnectionState.Error("RT-950 Pro not found")
                } else {
                    BleConnectionState.Idle
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    fun connect(address: String) {
        val device = knownDevices[address] ?: adapter?.getRemoteDevice(address)
        if (device == null) {
            _connectionState.value = BleConnectionState.Error("Unknown device")
            return
        }
        connect(device)
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        kissDecoder.reset()
        _connectionState.value = BleConnectionState.Connecting(device.name ?: device.address)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        mtuFallbackJob?.cancel()
        writeJob?.cancel()
        writeJob = null
        cleanupGatt()
        if (_connectionState.value !is BleConnectionState.Idle) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    fun clearTxError() {
        _lastTxError.value = null
    }

    /** GATT OK but the radio did not echo the frame back on FFE1 (no PTT/mirror). */
    fun reportNoRfEcho() {
        _lastTxError.value =
            "BLE OK on FF31, but no RF echo. The firmware may not key PTT from phone KISS."
    }

    fun sendAx25Payload(payload: ByteArray): Boolean {
        if (_connectionState.value !is BleConnectionState.Connected) {
            _lastTxError.value = "Radio not connected"
            return false
        }
        if (txChannels.isEmpty()) {
            _lastTxError.value = "TX channel not ready"
            return false
        }
        // A single KISS data frame (cmd 0x00). TXDELAY is not reliably implemented
        // in the RT-950's internal TNC.
        val kiss = KissCodec.encode(payload)
        val label = txChannels.first().label
        Log.i(TAG, "TX KISS ${kiss.size}B → $label: ${kiss.toHex()}")
        _lastTxError.value = null
        if (outbound.trySend(kiss).isFailure) {
            _lastTxError.value = "TX queue full"
            return false
        }
        return true
    }

    private fun handleIncoming(chunk: ByteArray) {
        kissDecoder.feed(chunk).forEach { payload ->
            if (payload.isNotEmpty()) {
                _kissPayloads.tryEmit(payload)
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt, notifyChar: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(notifyChar, true)
        val cccd = notifyChar.getDescriptor(BleUartProfile.CCCD_UUID) ?: run {
            _connectionState.value = BleConnectionState.Error("CCCD missing")
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun startWriteLoop() {
        writeJob?.cancel()
        writeJob = scope.launch {
            for (stream in outbound) {
                val ok = gattMutex.withLock { transmitKissStream(stream) }
                if (!ok) {
                    _lastTxError.value = "BLE TX failed (is FF31 / KISS TX enabled on the radio?)"
                    Log.e(TAG, "TX failed on FF31")
                } else {
                    // Give the internal TNC time between KISS frames / before PTT
                    delay(80)
                }
            }
        }
    }

    /** Writes the KISS frame on FF31 (the only channel in BLE KISS mode). */
    private suspend fun transmitKissStream(stream: ByteArray): Boolean {
        val channel = txChannels.firstOrNull() ?: return false
        Log.i(TAG, "TX over ${channel.label} (${stream.size}B)…")
        val ok = writeStreamToChannel(stream, channel)
        if (ok) Log.i(TAG, "TX GATT OK over ${channel.label}") else Log.e(TAG, "TX GATT failed over ${channel.label}")
        return ok
    }

    private suspend fun writeStreamToChannel(stream: ByteArray, channel: TxChannel): Boolean {
        val g = gatt ?: return false
        val chunkSize = maxWritePayload
        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + chunkSize, stream.size)
            val slice = stream.copyOfRange(offset, end)
            if (!writeOnMainThread(g, channel, slice)) return false
            offset = end
        }
        return true
    }

    /** A GATT write must run on the main thread (Android requirement). */
    private suspend fun writeOnMainThread(
        g: BluetoothGatt,
        channel: TxChannel,
        data: ByteArray
    ): Boolean {
        val needsAck = channel.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ack = if (needsAck) CompletableDeferred<Boolean>() else null
        writeAck = ack

        val accepted = suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(channel.characteristic, data, channel.writeType) == 0
                } else {
                    @Suppress("DEPRECATION")
                    channel.characteristic.value = data
                    @Suppress("DEPRECATION")
                    channel.characteristic.writeType = channel.writeType
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(channel.characteristic)
                }
                cont.resume(ok)
            }
        }

        if (!accepted) {
            writeAck = null
            return false
        }

        return if (ack != null) {
            val ok = withTimeoutOrNull(5_000) { ack.await() } == true
            writeAck = null
            if (!ok) Log.e(TAG, "GATT write without ACK (${channel.label}, ${data.size}B)")
            ok
        } else {
            delay(50)
            writeAck = null
            true
        }
    }

    private fun cleanupGatt() {
        mtuFallbackJob?.cancel()
        runCatching { gatt?.close() }
        gatt = null
        txChannels = emptyList()
        maxWritePayload = 20
        mtuReady = false
        kissDecoder.reset()
        writeAck?.complete(false)
        writeAck = null
    }

    companion object {
        private const val TAG = "BleUartManager"
        private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    }
}
