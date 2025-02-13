// 文件路径: module-radar/src/main/java/com/radar/ble/manager/RadarBleManager.kt

package com.radar.ble.manager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import blufi.espressif.BlufiCallback
import blufi.espressif.BlufiClient
import blufi.espressif.BlufiLog
import com.radar.ble.command.RadarCommand
import com.radar.ble.command.RadarResponse
import java.util.concurrent.ConcurrentHashMap


/**
 * 雷达设备蓝牙管理类
 * 基于 ESP BlufiClient 实现，处理设备扫描、连接和通信
 */
class RadarBleManager private constructor(private val context: Context) {

    //region ---------- 常量和基础定义 ----------
    private val log = BlufiLog(RadarBleManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceClients = ConcurrentHashMap<String, BlufiClient>()
    private val scanResults = ConcurrentHashMap<String, ScanResult>()
    private val commandQueue = ConcurrentHashMap<String, MutableList<CommandRequest>>()

    private var scanning = false
    private var filterEnabled = false

    private var scanCallback: ((RadarScanResult) -> Unit)? = null
    private var stateCallback: ((String, DeviceState) -> Unit)? = null
    private var responseCallback: ((String, RadarResponse) -> Unit)? = null
    private var errorCallback: ((String, RadarError) -> Unit)? = null

    companion object {
        private const val SCAN_PERIOD = 4000L          // 扫描超时时间
        private const val COMMAND_TIMEOUT = 5000L      // 命令超时时间
        private const val RECONNECT_DELAY = 5000L      // 重连延迟时间
        private const val MAX_RETRY_COUNT = 3          // 最大重试次数
        private const val DEVICE_NAME_PREFIX = "TSBLU" // 雷达设备前缀

        @Volatile
        private var instance: RadarBleManager? = null

        fun getInstance(context: Context): RadarBleManager {
            return instance ?: synchronized(this) {
                instance ?: RadarBleManager(context.applicationContext).also { instance = it }
            }
        }
    }
    //endregion

    //region ---------- 数据类和枚举定义 ----------
    data class RadarScanResult(
        val device: BluetoothDevice,
        val name: String?,
        val rssi: Int
    )

    private data class CommandRequest(
        val command: RadarCommand,
        var timestamp: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )

    enum class DeviceState {
        DISCONNECTED,  // 断开连接
        CONNECTING,    // 正在连接
        CONNECTED,     // 已连接
        READY         // 设备就绪(服务发现完成)
    }

    sealed class RadarError {
        object ScanFailed : RadarError()
        object ConnectionFailed : RadarError()
        object ServiceDiscoveryFailed : RadarError()
        object CommandTimeout : RadarError()
        object InvalidResponse : RadarError()
        data class CommandError(val code: Int) : RadarError()
        data class GattError(val status: Int) : RadarError()
    }
    //endregion

    //region ---------- 回调设置方法 ----------
    fun setScanCallback(callback: (RadarScanResult) -> Unit) {
        scanCallback = callback
    }

    fun setStateCallback(callback: (String, DeviceState) -> Unit) {
        stateCallback = callback
    }

    fun setResponseCallback(callback: (String, RadarResponse) -> Unit) {
        responseCallback = callback
    }

    fun setErrorCallback(callback: (String, RadarError) -> Unit) {
        errorCallback = callback
    }
    //endregion

    //region ---------- 扫描功能实现 ----------
    fun enableFilter(enable: Boolean) {
        filterEnabled = enable
    }

    @SuppressLint("MissingPermission")
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: return

            // 根据过滤开关判断是否需要过滤设备
            if (filterEnabled && !deviceName.startsWith(DEVICE_NAME_PREFIX)) {
                return
            }

            scanResults[device.address] = result

            val scanResult = RadarScanResult(
                device = device,
                name = deviceName,
                rssi = result.rssi
            )
            mainHandler.post {
                scanCallback?.invoke(scanResult)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log.e("Scan Failed: $errorCode")
            stopScan()
            notifyError("", RadarError.ScanFailed)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) {
            return
        }

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        if (scanner == null) {
            log.e("BLE scanner not available")
            return
        }

        scanResults.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, leScanCallback)
        scanning = true

        mainHandler.postDelayed({
            stopScan()
        }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) {
            return
        }

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        if (scanner != null) {
            scanner.stopScan(leScanCallback)
        }

        scanning = false
    }
//endregion

    //region ---------- 连接管理实现 ----------
    @SuppressLint("MissingPermission")
    private fun createGattCallback(deviceAddress: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionFailure(gatt, deviceAddress, status)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    notifyStateChange(deviceAddress, DeviceState.CONNECTED)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    handleDisconnect(gatt, deviceAddress)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError(deviceAddress, RadarError.ServiceDiscoveryFailed)
                gatt.disconnect()
                return
            }
            notifyStateChange(deviceAddress, DeviceState.READY)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleNotificationData(deviceAddress, characteristic.value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionFailure(gatt: BluetoothGatt, deviceAddress: String, status: Int) {
        log.e("Connection failed for device $deviceAddress, status: $status")
        gatt.close()
        deviceClients.remove(deviceAddress)
        notifyStateChange(deviceAddress, DeviceState.DISCONNECTED)
        notifyError(deviceAddress, RadarError.GattError(status))
        scheduleReconnect(deviceAddress)
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(gatt: BluetoothGatt, deviceAddress: String) {
        log.d("Device disconnected: $deviceAddress")
        gatt.close()
        deviceClients.remove(deviceAddress)
        notifyStateChange(deviceAddress, DeviceState.DISCONNECTED)
        scheduleReconnect(deviceAddress)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect(deviceAddress: String) {
        val scanResult = scanResults[deviceAddress] ?: return

        mainHandler.postDelayed({
            if (deviceClients[deviceAddress] == null) {
                log.d("Attempting to reconnect to device: $deviceAddress")
                connect(scanResult.device)
            }
        }, RECONNECT_DELAY)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val deviceAddress = device.address
        if (deviceClients.containsKey(deviceAddress)) {
            log.w("Device $deviceAddress already connected")
            return
        }

        notifyStateChange(deviceAddress, DeviceState.CONNECTING)

        val client = BlufiClient(context, device).apply {
            setGattWriteTimeout(COMMAND_TIMEOUT)
            setBlufiCallback(createBlufiCallback(deviceAddress))
        }
        deviceClients[deviceAddress] = client
        commandQueue[deviceAddress] = mutableListOf()
        client.connect()
    }

    @SuppressLint("MissingPermission")
    fun disconnect(deviceAddress: String) {
        deviceClients[deviceAddress]?.let { client ->
            commandQueue.remove(deviceAddress)
            client.close()
            deviceClients.remove(deviceAddress)
            notifyStateChange(deviceAddress, DeviceState.DISCONNECTED)
        }
    }
    //endregion

    //region ---------- BluFi回调实现 ----------
    private fun createBlufiCallback(deviceAddress: String) = object : BlufiCallback() {
        override fun onGattPrepared(
            client: BlufiClient,
            gatt: BluetoothGatt,
            service: BluetoothGattService?,
            writeChar: BluetoothGattCharacteristic?,
            notifyChar: BluetoothGattCharacteristic?
        ) {
            if (service == null || writeChar == null || notifyChar == null) {
                notifyError(deviceAddress, RadarError.ServiceDiscoveryFailed)
                return
            }
            notifyStateChange(deviceAddress, DeviceState.READY)
        }

        override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
            if (status == STATUS_SUCCESS) {
                handleCommandResponse(deviceAddress, data)
            } else {
                notifyError(deviceAddress, RadarError.CommandError(status))
            }
        }

        override fun onError(client: BlufiClient, errCode: Int) {
            notifyError(deviceAddress, RadarError.CommandError(errCode))
        }
    }
    //endregion

    //region ---------- 命令处理实现 ----------
    fun sendCommand(deviceAddress: String, command: RadarCommand) {
        val client = deviceClients[deviceAddress] ?: run {
            notifyError(deviceAddress, RadarError.ConnectionFailed)
            return
        }

        val request = CommandRequest(command)
        commandQueue[deviceAddress]?.add(request)

        try {
            val data = command.toBytes()
            client.postCustomData(data)
            scheduleTimeoutCheck(deviceAddress, request)
        } catch (e: Exception) {
            log.e("Send command failed: ${e.message}")
            commandQueue[deviceAddress]?.remove(request)
            notifyError(deviceAddress, RadarError.CommandError(-1))
        }
    }

    private fun scheduleTimeoutCheck(deviceAddress: String, request: CommandRequest) {
        mainHandler.postDelayed({
            checkCommandTimeout(deviceAddress, request)
        }, COMMAND_TIMEOUT)
    }

    private fun checkCommandTimeout(deviceAddress: String, request: CommandRequest) {
        val commands = commandQueue[deviceAddress] ?: return
        if (!commands.contains(request)) {
            return
        }

        if (System.currentTimeMillis() - request.timestamp > COMMAND_TIMEOUT) {
            if (request.retryCount < MAX_RETRY_COUNT) {
                request.retryCount++
                request.timestamp = System.currentTimeMillis()
                log.d("Retrying command, attempt ${request.retryCount}")

                deviceClients[deviceAddress]?.let { client ->
                    try {
                        client.postCustomData(request.command.toBytes())
                        scheduleTimeoutCheck(deviceAddress, request)
                    } catch (e: Exception) {
                        handleCommandFailure(deviceAddress, request)
                    }
                }
            } else {
                handleCommandFailure(deviceAddress, request)
            }
        }
    }

    private fun handleCommandFailure(deviceAddress: String, request: CommandRequest) {
        commandQueue[deviceAddress]?.remove(request)
        notifyError(deviceAddress, RadarError.CommandTimeout)
    }

    private fun handleCommandResponse(deviceAddress: String, data: ByteArray) {
        try {
            val response = RadarResponse.fromBytes(data)
            commandQueue[deviceAddress]?.removeFirstOrNull()
            notifyResponse(deviceAddress, response)
        } catch (e: Exception) {
            log.e("Parse response failed: ${e.message}")
            notifyError(deviceAddress, RadarError.InvalidResponse)
        }
    }

    private fun handleNotificationData(deviceAddress: String, data: ByteArray) {
        try {
            val response = RadarResponse.fromBytes(data)
            notifyResponse(deviceAddress, response)
        } catch (e: Exception) {
            log.e("Parse notification failed: ${e.message}")
        }
    }
    //endregion

    //region ---------- 通知回调实现 ----------
    private fun notifyStateChange(deviceAddress: String, state: DeviceState) {
        mainHandler.post {
            stateCallback?.invoke(deviceAddress, state)
        }
    }

    private fun notifyResponse(deviceAddress: String, response: RadarResponse) {
        mainHandler.post {
            responseCallback?.invoke(deviceAddress, response)
        }
    }

    private fun notifyError(deviceAddress: String, error: RadarError) {
        mainHandler.post {
            errorCallback?.invoke(deviceAddress, error)
        }
    }
    //endregion
}