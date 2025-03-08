/**
 * File: RadarBleManager.kt
 * Path: module-radar/src/main/java/com/espressif/espblufi/RadarBleManager.kt
 *
 * A厂(Radar)蓝牙管理类，封装 BlufiClient 实现
 * */
package com.espressif.espblufi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.MacAddress
import android.net.wifi.WifiSsid
import android.os.Handler
import android.os.Looper
import android.util.Log

//common data type
import com.common.DeviceInfo
import com.common.Productor
import com.common.FilterType
import com.common.DeviceHistory
import com.common.ServerConfig
import com.common.WifiConfig
import com.common.DefaultConfig
import android.os.Build

import com.espressif.espblufi.constants.BlufiConstants
import com.espressif.espblufi.params.BlufiConfigureParams
import com.espressif.espblufi.response.BlufiStatusResponse
import com.espressif.espblufi.response.BlufiVersionResponse


/**
 * A厂雷达设备蓝牙管理类
 * - 扫描: 使用系统原生扫描，支持多种过滤方式
 * - 通信: 封装 BlufiClient，负责连接和配网
 */
@SuppressLint("MissingPermission")
class RadarBleManager private constructor(private val context: Context) {

    //region 参数定义
    // -------------- 1. 参数定义 --------------
    companion object {
        private const val TAG = "RadarBleManager"
        private const val SCAN_TIMEOUT = 10000L  // 扫描超时时间 10秒
        private const val QUERY_TIMEOUT = 25000L  // 查询超时时间 25秒
        private const val GATT_WRITE_TIMEOUT = 10000L //连接超时间10秒
        private const val CONFIGSERVER_TIMEOUT=25000L
        private const val COMMAND_DELAYTIME=1000L
        private const val DEVICERESTART_DELAYTIME=5000L

        // 错误处理相关常量
        private const val MAX_RETRY_COUNT = 3
        private const val RECONNECT_DELAY = 1000L  // 1秒
        private const val RETRY_DELAY = 500L       // 500毫秒
        private const val MAX_ERROR_THRESHOLD = 5  // 最大错误阈值

        // 添加 keepAlive 相关常量
        private const val KEEP_ALIVE_INTERVAL = 3000L // 3秒发送一次保活信号


        @Volatile
        private var instance: RadarBleManager? = null

        fun getInstance(context: Context): RadarBleManager {
            return instance ?: synchronized(this) {
                instance ?: RadarBleManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // 添加 keepAlive 相关成员变量
    private var isKeepAliveRunning = false
    private lateinit var keepAliveRunnable: Runnable

    // Then add this initialization in the init block or constructor:
    init {
        keepAliveRunnable = Runnable {
            if (isConnecting && blufiClient != null) {
                Log.d(TAG, "Sending keep-alive signal")
                try {
                    // 发送一个轻量级命令来保持连接活跃
                    blufiClient?.postCustomData("65:".toByteArray())
                } catch (e: Exception) {
                    Log.e(TAG, "Keep-alive error", e)
                }

                // 安排下一次保活信号
                if (isKeepAliveRunning) {
                    mainHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
                }
            }
        }
    }


    /**
     * 启动 keepAlive 机制
     */
    private fun startKeepAlive() {
        if (!isKeepAliveRunning) {
            Log.d(TAG, "Starting keep-alive mechanism")
            isKeepAliveRunning = true
            mainHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
        }
    }

    /**
     * 停止 keepAlive 机制
     */
    private fun stopKeepAlive() {
        if (isKeepAliveRunning) {
            Log.d(TAG, "Stopping keep-alive mechanism")
            isKeepAliveRunning = false
            mainHandler.removeCallbacks(keepAliveRunnable)
        }
    }


    // 错误类型枚举
    enum class ErrorType {
        CONNECTION_TIMEOUT,
        SECURITY_ERROR,
        DATA_ERROR,
        UNKNOWN
    }

    // 添加错误处理相关变量
    private var notifyErrorCallback: ((ErrorType, String) -> Unit)? = null
    private var errorCount = 0
    private var isRetryEnabled = true
    private var isConnecting = false
    private var currentDeviceMac: String? = null  // 存储当前连接的设备MAC地址

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var blufiClient: BlufiClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanTimeoutRunnable = Runnable {
        Log.d(TAG, "Scan timeout")
        stopScan()
    }

    private var isScanning = false
    private var filterPrefix: String? = null
    private var filterType: FilterType = FilterType.DEVICE_NAME
    private var configureCallback: ((Boolean) -> Unit)? = null

    // 扫描回调
    private var scanCallback: ((DeviceInfo) -> Unit)? = null

    //endregion

    //region 基础函数及扩展函数
    // -------------- 2. 基础函数 --------------
    /**
     * 设置错误回调
     */
    fun setErrorCallback(callback: (ErrorType, String) -> Unit) {
        notifyErrorCallback = callback
    }

    /**
     * 启用/禁用自动重试
     */
    fun enableRetry(enable: Boolean) {
        isRetryEnabled = enable
    }

    /**
     * 重置错误计数
     */
    fun resetErrorCount() {
        errorCount = 0
    }


    /**
     * 设置扫描回调
     */
    fun setScanCallback(callback: (DeviceInfo) -> Unit) {
        scanCallback = callback
    }


    /**
     * 连接设备
     */
    fun connect(deviceMacAddress: String) {
        val device = bluetoothAdapter?.getRemoteDevice(deviceMacAddress)

        if (device != null) {
            Log.d(TAG, "Device found, starting connection process")
            isConnecting = true
            currentDeviceMac = deviceMacAddress
            connect(device)
        } else {
            Log.e(TAG, "Failed to get device with MAC: $deviceMacAddress")
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Invalid device address")
            // 处理错误
        }
    }

    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "Starting connection to device: ${device.address}")
        disconnect()

        BlufiClient(context, device).also { client ->
            blufiClient = client

            // 设置 GATT 回调
            client.setGattCallback(createGattCallback())
            client.setBlufiCallback(createBlufiCallback())

            // 设置超时 BlufiConstants.GATT_WRITE_TIMEOUT=5000L->10000L
            //client.setGattWriteTimeout(BlufiConstants.GATT_WRITE_TIMEOUT)
            client.setGattWriteTimeout(GATT_WRITE_TIMEOUT)

            // 重置MTU和错误计数
            errorCount = 0

            // 开始连接
            Log.d(TAG, "Initiating GATT connection to device: ${device.address}")
            client.connect()
        }
    }

    /**
     * 重新连接设备
     */
    private fun reconnect() {
        if (isConnecting) return

        val deviceMac = currentDeviceMac ?: return
        Log.d(TAG, "Attempting reconnection to $deviceMac")

        disconnect()
        // 短暂延迟确保断开完全处理
        mainHandler.postDelayed({
            connect(deviceMac)
        }, 200)
    }

    /**
     * 重置安全状态
     */
    private fun resetSecurityState() {
        // 重置与安全协商相关的状态
        Log.d(TAG, "Resetting security state")
        // 执行任何必要的状态重置
    }



    /**
     * 获取设备版本
     */
    fun requestDeviceVersion() {
        blufiClient?.requestDeviceVersion()
    }

    /**
     * 获取设备状态
     */
    fun requestDeviceStatus() {
        blufiClient?.requestDeviceStatus()
    }

    /**
     * 配置设备
     */
    fun configure(params: BlufiConfigureParams, callback: ((Boolean) -> Unit)? = null) {
        configureCallback = callback
        blufiClient?.let { client ->
            // 先进行安全协商
            client.negotiateSecurity()
            // 发送配置
            client.configure(params)
        } ?: run {
            callback?.invoke(false)
        }
    }


    /**
     * 发送自定义数据
     */
    fun postCustomData(data: ByteArray) {
        blufiClient?.postCustomData(data)
    }


    /**
     * 断开连接
     */
    fun disconnect() {
        isConnecting = false

        // 移除所有挂起的回调以防止内存泄漏
        mainHandler.removeCallbacksAndMessages(null)

        // 直接同步执行关闭操作，不使用延迟,否则在connect开始调用，会中断Rigster
        try {
            blufiClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BluetoothGatt", e)
        }
        blufiClient = null
        configureCallback = null
    }

    /**
     * 释放资源
     */
    fun release() {
        stopScan()
        disconnect()
        mainHandler.removeCallbacksAndMessages(null)
        currentDeviceMac = null
        instance = null
    }

    private fun createGattCallback(): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Connected to device: ${gatt.device.address}")
                            // 连接成功后立即启动保活
                            startKeepAlive()
                            // 请求高优先级连接（对所有版本）
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            // 增加延迟时间，确保连接参数设置完成
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isConnecting) { // 确保仍然在连接过程中
                                    gatt.discoverServices()
                                }
                            }, 150) // 稍微增加延迟
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Disconnected from device: ${gatt.device.address}")
                            // 连接断开时停止保活
                            stopKeepAlive()
                            disconnect()
                        }
                    }
                } else if (status == 8 && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // 状态码 8 通常表示连接超时或远程设备断开
                    Log.e(TAG, "Connection failed with status 8 (timeout), attempting reconnect")

                    // 如果在连接过程中且未超过最大重试次数
                    if (isConnecting && errorCount < MAX_RETRY_COUNT) {
                        errorCount++
                        // 停止现有保活
                        stopKeepAlive()
                        // 重新连接设备
                        Handler(Looper.getMainLooper()).postDelayed({
                            val device = bluetoothAdapter?.getRemoteDevice(currentDeviceMac)
                            if (device != null) {
                                Log.d(TAG, "Reconnecting after status 8 disconnect... Attempt: $errorCount")
                                disconnect()
                                connect(device)
                            }
                        }, RECONNECT_DELAY)
                    } else {
                        Log.e(TAG, "Connection failed with status 8, max retries exceeded or not in connecting state")
                        stopKeepAlive()
                        disconnect()
                    }
                } else {
                    // 其他连接失败情况
                    Log.e(TAG, "Connection failed with status: $status")
                    stopKeepAlive()
                    disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered successfully")
                    // 在服务发现成功后也启动保活
                    startKeepAlive()
                } else {
                    Log.e(TAG, "Service discovery failed with status: $status")
                }
            }

            // 添加 MTU 变化回调
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU changed to: $mtu")
                } else {
                    Log.e(TAG, "MTU change failed with status: $status")
                }
            }

            // 删除有问题的 onConnectionUpdated 方法
        }
    }

    private fun createBlufiCallback(): BlufiCallback {
        return object : BlufiCallback() {
            override fun onGattPrepared(
                client: BlufiClient,
                gatt: BluetoothGatt,
                service: BluetoothGattService?,
                writeChar: BluetoothGattCharacteristic?,
                notifyChar: BluetoothGattCharacteristic?
            ) {
                Log.d(TAG, "onGattPrepared: service=${service != null}, write=${writeChar != null}, notify=${notifyChar != null}")
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    disconnect()
                    return
                }
                Log.d(TAG, "GATT services prepared successfully")
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                Log.d(TAG, "Security negotiation result: $status")
                if (status == STATUS_SUCCESS) {
                    Log.d(TAG, "Security negotiation successful")
                } else {
                    Log.e(TAG, "Security negotiation failed with status: $status")
                }
            }

            override fun onPostConfigureParams(client: BlufiClient, status: Int) {
                Log.d(TAG, "Configure result: $status")
                configureCallback?.invoke(status == STATUS_SUCCESS)
                configureCallback = null
            }

            override fun onDeviceStatusResponse(
                client: BlufiClient,
                status: Int,
                response: BlufiStatusResponse
            ) {
                Log.d(TAG, "Device status: $status, response: $response")
            }

            override fun onDeviceVersionResponse(
                client: BlufiClient,
                status: Int,
                response: BlufiVersionResponse
            ) {
                Log.d(TAG, "Device version: $status, response: $response")
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                Log.d(TAG, "Received custom data, status: $status")
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                Log.e(TAG, "BluFi error: $errCode")

                // 记录错误次数
                errorCount++

                when (errCode) {
                    CODE_GATT_WRITE_TIMEOUT -> {
                        Log.e(TAG, "GATT write operation timed out with current MTU}")
                        disconnect()
                        notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT,
                            "Connection timed out after $errorCount attempts")
                    }

                    CODE_NEG_ERR_DEV_KEY -> {
                        Log.e(TAG, "Security negotiation failed: invalid device key")
                        resetSecurityState()
                        disconnect()
                        notifyErrorCallback?.invoke(ErrorType.SECURITY_ERROR,
                            "Failed to negotiate security")
                    }

                    CODE_INVALID_NOTIFICATION, CODE_NEG_ERR_SECURITY -> {
                        Log.e(TAG, "Invalid data received or security error")
                        disconnect()
                        notifyErrorCallback?.invoke(ErrorType.DATA_ERROR,
                            "Communication error occurred")
                    }

                    else -> {
                        Log.e(TAG, "Unknown error: $errCode")
                        if (errorCount >= MAX_ERROR_THRESHOLD) {
                            disconnect()
                            notifyErrorCallback?.invoke(ErrorType.UNKNOWN,
                                "Multiple errors occurred: $errCode")
                        }
                    }
                }
            }
        }
    }

    // 添加带间隔参数的保活启动方法
    private fun startKeepAliveWithInterval(interval: Long) {
        if (isKeepAliveRunning) {
            stopKeepAlive()
        }
        Log.d(TAG, "Starting keep-alive mechanism with interval: $interval ms")
        isKeepAliveRunning = true
        mainHandler.postDelayed(keepAliveRunnable, interval)
    }

    //endregion

    //region  扫描BleResult
    // -------------- 3. 扫描相关函数 --------------

    //region  扫描BleResult
    // -------------- 3. 扫描相关函数 --------------

    /**
     * 开始扫描
     * @param filterPrefix 过滤值，null 或空值时不过滤
     * @param filterType 过滤类型，默认为设备名称过滤
     */
    fun startScan(filterPrefix: String?, filterType: FilterType = FilterType.DEVICE_NAME) {
        if (isScanning) return
        Log.d(
            TAG,
            "RadarBleManager startScan with filterPrefix: '$filterPrefix', filterType: $filterType"
        )
        this.filterPrefix = filterPrefix
        this.filterType = filterType

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = true

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, leScanCallback)
                mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT)
            } catch (e: Exception) {
                Log.e(TAG, "Start scan failed: ${e.message}")
            }
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!isScanning) return

        mainHandler.removeCallbacks(scanTimeoutRunnable)

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = false
            try {
                scanner.stopScan(leScanCallback)
                Log.d(TAG, "Scan stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Stop scan failed: ${e.message}")
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 只有在设置了过滤条件时才进行过滤
            val prefix = filterPrefix
            if (!prefix.isNullOrEmpty()) {
                when (filterType) {
                    FilterType.DEVICE_NAME -> {
                        val deviceName = result.device.name
                        if (deviceName == null || !deviceName.contains(prefix, ignoreCase = true)) {
                            return
                        }
                    }

                    FilterType.MAC -> {
                        val deviceMac = result.device.address
                        if (!deviceMac.replace(":", "")
                                .replace("-", "")
                                .contains(prefix, ignoreCase = true)
                        ) {
                            return
                        }
                    }

                    FilterType.UUID -> {
                        val scanRecord = result.scanRecord ?: return
                        val serviceUuids = scanRecord.serviceUuids ?: return
                        val matchFound = serviceUuids.any { uuid ->
                            uuid.toString().contains(prefix, ignoreCase = true)
                        }
                        if (!matchFound) {
                            return
                        }
                    }
                }
            }

            // 转换为 DeviceInfo
            val deviceInfo = DeviceInfo(
                productorName = Productor.radarQL,
                deviceName = result.device.name ?: "Unknown",
                deviceId = result.device.name ?: result.device.address,
                macAddress = result.device.address,
                rssi = result.rssi,
           )

            mainHandler.post {
                scanCallback?.invoke(deviceInfo)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false
        }
    }
    //endregion

    //region  查询RadarQL设备状态
    // -------------- 4. 连接和查询相关函数 --------------
    /**
     * 查询RadarQL设备状态
     * 依次查询：UID -> WiFi Status -> Server Status
     */
    fun queryDeviceStatus(
        deviceInfo: DeviceInfo,
        callback: ((Map<String, String>) -> Unit)? = null
    ) {
        Log.d(
            TAG,
            "Start query device status for: ${deviceInfo.deviceId}, MAC: ${deviceInfo.macAddress}"
        )

        val device = bluetoothAdapter?.getRemoteDevice(deviceInfo.macAddress) ?: run {
            callback?.invoke(mapOf("error" to "Invalid device address"))
            return
        }

        // 存储查询结果
        val statusMap = mutableMapOf<String, String>()

        // 查询状态控制
        var isQueryComplete = false
        var hasWifiStatus = false
        var hasUID = false

        // 查询超时
        val queryTimeoutRunnable = Runnable {
            Log.e(TAG, "Query timeout after  ${QUERY_TIMEOUT/1000} seconds")
            if (!isQueryComplete) {
                isQueryComplete = true
                statusMap["error"] = "Query timeout"
                callback?.invoke(statusMap)
                disconnect()
            }
        }

        // 连接设备
        Log.d(TAG, "Connecting to device: ${device.address}")
        connect(device)

        // 设置回调
        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(
                client: BlufiClient,
                gatt: BluetoothGatt,
                service: BluetoothGattService?,
                writeChar: BluetoothGattCharacteristic?,
                notifyChar: BluetoothGattCharacteristic?
            ) {
                Log.d(
                    TAG,
                    "onGattPrepared: service=${service != null}, write=${writeChar != null}, notify=${notifyChar != null}"
                )

                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    if (!isQueryComplete) {
                        isQueryComplete = true
                        statusMap["error"] = "Service discovery failed"
                        callback?.invoke(statusMap)
                    }
                    disconnect()
                    return
                }

                // 设置超时
                mainHandler.postDelayed(queryTimeoutRunnable, QUERY_TIMEOUT)

                // 先进行安全协商
                Log.d(TAG, "Starting security negotiation")
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                Log.d(TAG, "Security negotiation result: $status")

                if (status == STATUS_SUCCESS) {
                    // 协商成功，开始查询设备状态
                    Log.d(TAG, "Requesting device status...")
                    client.requestDeviceStatus()
                } else {
                    Log.e(TAG, "Security negotiation failed with status: $status")
                    if (!isQueryComplete) {
                        isQueryComplete = true
                        statusMap["error"] = "Security negotiation failed: $status"
                        callback?.invoke(statusMap)
                        mainHandler.removeCallbacks(queryTimeoutRunnable)
                        disconnect()
                    }
                }
            }

            override fun onDeviceStatusResponse(
                client: BlufiClient,
                status: Int,
                response: BlufiStatusResponse
            ) {
                Log.d(TAG, "=================== DEVICE STATUS RESPONSE BEGIN ===================")
                Log.d(TAG, "Status code: $status")

                if (status == STATUS_SUCCESS) {
                    // 尝试通过反射获取所有字段
                    try {
                        Log.d(TAG, "----- Extracting fields from status response -----")
                        val fields = response.javaClass.declaredFields
                        for (field in fields) {
                            field.isAccessible = true
                            try {
                                val value = field.get(response)
                                Log.d(TAG, "${field.name}: $value")
                            } catch (e: Exception) {
                                Log.d(TAG, "${field.name}: [Error accessing: ${e.message}]")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accessing fields via reflection: ${e.message}")
                    }

                    // 使用 statusMap 记录重要信息
                    hasWifiStatus = true
                    statusMap["wifiOpMode"] = when (response.opMode) {
                        0 -> "NULL"
                        1 -> "STA"
                        2 -> "SOFTAP"
                        3 -> "STASOFTAP"
                        else -> "UNKNOWN(${response.opMode})"
                    }

                    if (response.opMode == 1 || response.opMode == 3) { // STA 或 STASOFTAP
                        statusMap["staConnected"] = (response.staConnectionStatus == 0).toString()
                        statusMap["staSSID"] = response.staSSID ?: ""
                        statusMap["staBSSID"] = response.staBSSID ?: ""
                    }

                    if (response.opMode == 2 || response.opMode == 3) { // SOFTAP 或 STASOFTAP
                        statusMap["apSSID"] = response.softAPSSID ?: ""
                        statusMap["apSecurity"] = response.softAPSecurity.toString()
                        statusMap["apChannel"] = response.softAPChannel.toString()
                        statusMap["apConnCount"] = response.softAPConnectionCount.toString()
                    }

                    // 继续执行后续操作
                    Log.d(TAG, "Requesting device UID...")
                    client.postCustomData("12:".toByteArray())
                } else {
                    Log.e(TAG, "Failed to get device status, status code: $status")
                    // 错误处理代码...
                }

                Log.d(TAG, "=================== DEVICE STATUS RESPONSE END ===================")
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                Log.d(TAG, "=================== CUSTOM DATA RESPONSE BEGIN ===================")
                Log.d(TAG, "Status code: $status")
                Log.d(TAG, "Raw data length: ${data.size} bytes")
                Log.d(TAG, "Raw data bytes: ${data.contentToString()}")

                // 转换为十六进制
                val hexString = data.joinToString("") { "%02X".format(it) }
                Log.d(TAG, "Data as hex: $hexString")

                // 转换为字符串
                try {
                    val stringData = String(data)
                    Log.d(TAG, "Data as string: '$stringData'")

                    // 尝试解析
                    if (stringData.contains(":")) {
                        val parts = stringData.split(":")
                        Log.d(TAG, "Split by ':' - parts count: ${parts.size}")
                        for (i in parts.indices) {
                            Log.d(TAG, "Part $i: '${parts[i]}'")
                        }

                        val command = parts.getOrNull(0)?.toIntOrNull()
                        Log.d(TAG, "Command part as integer: $command")

                        when (command) {
                            12 -> { // UID 响应
                                hasUID = true
                                Log.d(TAG, "Identified as UID response (command=12)")

                                if (status == STATUS_SUCCESS && parts.size >= 2) {
                                    val uid = parts[1].trim()
                                    statusMap["uid"] = uid
                                    Log.d(TAG, "Extracted UID: '$uid'")
                                } else {
                                    Log.e(
                                        TAG,
                                        "Failed to parse UID response, status=$status, parts.size=${parts.size}"
                                    )
                                    statusMap["uidError"] = "Failed to parse UID response"
                                }

                                // 检查查询状态
                                Log.d(
                                    TAG,
                                    "Query status: hasWifiStatus=$hasWifiStatus, hasUID=$hasUID"
                                )
                                if (hasWifiStatus || statusMap.containsKey("wifiError")) {
                                    if (!isQueryComplete) {
                                        isQueryComplete = true
                                        Log.d(TAG, "Query complete, returning results")
                                        Log.d(TAG, "Final statusMap: $statusMap")
                                        callback?.invoke(statusMap)
                                        mainHandler.removeCallbacks(queryTimeoutRunnable)
                                        disconnect()
                                    } else {
                                        Log.d(TAG, "Query already completed previously")
                                    }
                                } else {
                                    // 如果还没有 WiFi 状态，尝试查询
                                    Log.d(
                                        TAG,
                                        "UID received but no WiFi status yet, requesting device status"
                                    )
                                    client.requestDeviceStatus()
                                }
                            }

                            62 -> { // WiFi 状态响应 (如果使用自定义命令查询WiFi)
                                Log.d(TAG, "Identified as WiFi status response (command=62)")
                                Log.d(
                                    TAG,
                                    "WiFi status from custom command: ${parts.joinToString(":")}"
                                )

                                if (parts.size >= 3) {
                                    val mode = parts[1]
                                    val connected = parts[2] == "0"
                                    statusMap["customWifiMode"] = when (mode) {
                                        "1" -> "STA"
                                        "2" -> "AP"
                                        "3" -> "APSTA"
                                        else -> "Unknown"
                                    }
                                    statusMap["customWifiConnected"] = connected.toString()

                                    if (connected && parts.size > 3) {
                                        statusMap["customWifiSSID"] = parts[3]
                                        Log.d(TAG, "WiFi connected to SSID: ${parts[3]}")
                                    }
                                }
                            }

                            else -> {
                                Log.w(
                                    TAG,
                                    "Unknown custom data command: $command, response: $stringData"
                                )
                                statusMap["unknownResponse"] = stringData
                            }
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Custom data doesn't contain ':' separator, can't parse as command"
                        )
                        statusMap["unparsedResponse"] = stringData
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing custom data", e)
                    statusMap["parseError"] = "Failed to parse response: ${e.message}"
                    e.printStackTrace()
                }

                Log.d(TAG, "=================== CUSTOM DATA RESPONSE END ===================")
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                Log.e(TAG, "BluFi error: $errCode")
                if (!isQueryComplete) {
                    isQueryComplete = true
                    statusMap["error"] = "Communication error: $errCode"
                    callback?.invoke(statusMap)
                    mainHandler.removeCallbacks(queryTimeoutRunnable)
                    disconnect()
                }
            }
        })
    }

    //endregion

    //region 配网、配服务器
    // -------------- 5. 配网和服务器配置函数 --------------

    /**
     * 配置设备WiFi
     *
     * @param deviceInfo 设备信息
     * @param wifiConfig WiFi配置
     * @param callback 配置结果回调
     */
    fun configureWifi(
        macAdd: String,
        _ssid: String,
        _password:String,
        callback: ((Map<String, String>) -> Unit)? = null
    ) {
        Log.d(TAG, "Start configuring device WiFi:, SSID: ${_ssid}")

        val device = bluetoothAdapter?.getRemoteDevice(macAdd) ?: run {
            callback?.invoke(mapOf("error" to "Invalid device address"))
            return
        }

        // 初始化状态信息
        val resultMap = mutableMapOf<String, String>()


        // 配置状态控制
        var isComplete = false

        // 配置超时
        val configTimeoutRunnable = Runnable {
            Log.e(TAG, "WiFi configuration timeout after 20 seconds")
            if (!isComplete) {
                isComplete = true
                resultMap["error"] = "WiFi configuration timeout"
                resultMap["success"] = "false"
                callback?.invoke(resultMap)
                disconnect()
            }
        }

        // 连接设备
        Log.d(TAG, "Connecting to device: ${device.address}")
        connect(device)

        // 设置回调
        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(client: BlufiClient,
                                        gatt: BluetoothGatt,
                                        service: BluetoothGattService?,
                                        writeChar: BluetoothGattCharacteristic?,
                                        notifyChar: BluetoothGattCharacteristic?) {
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Service discovery failed"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        disconnect()
                    }
                    return
                }

                // 设置超时
                mainHandler.postDelayed(configTimeoutRunnable, 20000)

                // 开始安全协商
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                if (status == STATUS_SUCCESS) {
                    // 安全协商成功，开始配置WiFi
                    // 配置WiFi
                    Log.d(TAG, "Configuring WiFi: SSID=${_ssid}")
                    val params = BlufiConfigureParams().apply {
                        opMode = 1  // STA模式
                        staSSIDBytes = _ssid.toByteArray()
                        staPassword = _password
                    }
                    client.configure(params)
                } else {
                    // 安全协商失败
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Security negotiation failed: $status"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }
            }

            override fun onPostConfigureParams(client: BlufiClient, status: Int) {
                if (!isComplete) {
                    isComplete = true

                    if (status == STATUS_SUCCESS) {
                        // WiFi配置成功
                        resultMap["wifiConfigured"] = "true"
                        resultMap["success"] = "true"
                        Log.d(TAG, "WiFi configuration successful")
                    } else {
                        // WiFi配置失败
                        resultMap["wifiConfigured"] = "false"
                        resultMap["success"] = "false"
                        resultMap["error"] = "WiFi configuration failed: $status"
                        Log.e(TAG, "WiFi configuration failed with status: $status")
                    }

                    // 添加完成时间
                    resultMap["completedAt"] = System.currentTimeMillis().toString()

                    // 返回结果
                    callback?.invoke(resultMap)

                    // 清理资源
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                if (!isComplete) {
                    isComplete = true
                    resultMap["error"] = "Communication error: $errCode"
                    resultMap["success"] = "false"
                    resultMap["completedAt"] = System.currentTimeMillis().toString()
                    callback?.invoke(resultMap)
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }
        })
    }

    /**
     * 配置设备服务器
     *
     * @param deviceInfo 设备信息
     * @param serverConfig 服务器配置
     * @param callback 配置结果回调
     */
    fun configureServer(
        deviceInfo: DeviceInfo,
        serverConfig: ServerConfig,
        callback: ((Map<String, String>) -> Unit)? = null
    ) {
        Log.d(TAG, "Start configuring device server: ${deviceInfo.deviceId}, Server: ${serverConfig.serverAddress}:${serverConfig.port}")

        val device = bluetoothAdapter?.getRemoteDevice(deviceInfo.macAddress) ?: run {
            callback?.invoke(mapOf("error" to "Invalid device address"))
            return
        }

        // 初始化状态信息
        val resultMap = mutableMapOf<String, String>()
        resultMap["deviceId"] = deviceInfo.deviceId
        resultMap["macAddress"] = deviceInfo.macAddress

        // 配置状态控制
        var isComplete = false
        var addressConfigured = false
        var portConfigured = false
        var deviceRestarted = false

        // 配置超时
        val configTimeoutRunnable = Runnable {
            Log.e(TAG, "Server configuration timeout after 25 seconds")
            if (!isComplete) {
                isComplete = true
                resultMap["error"] = "Server configuration timeout"
                resultMap["success"] = "false"
                callback?.invoke(resultMap)
                disconnect()
            }
        }

        // 连接设备
        Log.d(TAG, "Connecting to device: ${device.address}")
        connect(device)

        // 设置回调
        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(client: BlufiClient,
                                        gatt: BluetoothGatt,
                                        service: BluetoothGattService?,
                                        writeChar: BluetoothGattCharacteristic?,
                                        notifyChar: BluetoothGattCharacteristic?) {
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Service discovery failed"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        disconnect()
                    }
                    return
                }

                // 设置超时
                mainHandler.postDelayed(configTimeoutRunnable, CONFIGSERVER_TIMEOUT)

                // 开始安全协商
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                if (status == STATUS_SUCCESS) {
                    // 安全协商成功，开始配置服务器
                    sendServerCommands(client)
                } else {
                    // 安全协商失败
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Security negotiation failed: $status"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }
            }

            private fun sendServerCommands(client: BlufiClient) {
                // 发送服务器地址
                val serverCmd = "1:${serverConfig.serverAddress}"
                Log.d(TAG, "Sending server address command: $serverCmd")
                client.postCustomData(serverCmd.toByteArray())

                // 等待服务器地址响应后再发送端口 - 延迟发送端口命令
                mainHandler.postDelayed({
                    if (!isComplete) {  // 检查是否已完成
                        // 发送服务器端口
                        val portCmd = "2:${serverConfig.port}"
                        Log.d(TAG, "Sending server port command: $portCmd")
                        client.postCustomData(portCmd.toByteArray())

                        // 等待端口响应后再发送额外命令
                        mainHandler.postDelayed({
                            if (!isComplete) {  // 再次检查是否已完成
                                // 发送必要的额外命令
                                sendExtraCommands(client)
                            }
                        }, COMMAND_DELAYTIME)
                    }
                }, COMMAND_DELAYTIME)
            }

            private fun sendExtraCommands(client: BlufiClient) {
                // 发送命令 3:0
                Log.d(TAG, "Sending extra command 3:0")
                client.postCustomData("3:0".toByteArray())

                // 等待响应后发送下一个命令
                mainHandler.postDelayed({
                    if (!isComplete) {  // 检查是否已完成
                        // 发送命令 8:0
                        Log.d(TAG, "Sending extra command 8:0")
                        client.postCustomData("8:0".toByteArray())

                        // 等待响应后重启设备
                        mainHandler.postDelayed({
                            if (!isComplete) {  // 再次检查是否已完成
                                // 重启设备
                                sendRestartCommand(client)
                            }
                        }, COMMAND_DELAYTIME)
                    }
                }, COMMAND_DELAYTIME)
            }

            private fun sendRestartCommand(client: BlufiClient) {
                // 发送重启命令
                Log.d(TAG, "Sending restart command 8:")
                client.postCustomData("8:".toByteArray())

                // 如果5秒后仍未收到重启响应，则认为重启已经开始但无法收到响应，认为配置完成
                mainHandler.postDelayed({
                    if (!isComplete) {
                        // 还未收到重启响应，但认为已经在重启中
                        isComplete = true

                        // 添加最终状态
                        resultMap["deviceRestarted"] = "unknown"

                        // 检查整体配置是否成功 - 至少服务器地址或端口配置成功
                        val success = addressConfigured || portConfigured
                        resultMap["success"] = success.toString()

                        // 添加完成时间
                        resultMap["completedAt"] = System.currentTimeMillis().toString()

                        // 返回结果
                        Log.d(TAG, "Configuration completed with timeout, result: $resultMap")
                        callback?.invoke(resultMap)

                        // 清理资源
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }, DEVICERESTART_DELAYTIME)
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                try {
                    val responseStr = String(data)
                    Log.d(TAG, "Received custom data: $responseStr")

                    // 解析响应格式 "命令:结果"
                    if (responseStr.contains(":")) {
                        val parts = responseStr.split(":")
                        if (parts.size >= 2) {
                            val command = parts[0].toIntOrNull()
                            val result = parts[1]

                            when (command) {
                                1 -> { // 服务器地址响应
                                    val success = result == "0"
                                    addressConfigured = success
                                    resultMap["serverAddressSuccess"] = success.toString()
                                    Log.d(TAG, "Server address configuration ${if (success) "successful" else "failed"}")
                                }
                                2 -> { // 服务器端口响应
                                    val success = result == "0"
                                    portConfigured = success
                                    resultMap["serverPortSuccess"] = success.toString()
                                    Log.d(TAG, "Server port configuration ${if (success) "successful" else "failed"}")
                                }
                                8 -> { // 重启设备响应
                                    val success = result == "0"
                                    deviceRestarted = success
                                    resultMap["deviceRestarted"] = success.toString()
                                    Log.d(TAG, "Device restart ${if (success) "successful" else "failed"}")

                                    // 收到重启响应，配置完成
                                    if (!isComplete) {
                                        isComplete = true

                                        // 检查整体配置是否成功 - 至少服务器地址或端口配置成功
                                        val success = addressConfigured || portConfigured
                                        resultMap["success"] = success.toString()

                                        // 添加完成时间
                                        resultMap["completedAt"] = System.currentTimeMillis().toString()

                                        // 返回结果
                                        Log.d(TAG, "Configuration complete, result: $resultMap")
                                        callback?.invoke(resultMap)

                                        // 清理资源
                                        mainHandler.removeCallbacks(configTimeoutRunnable)
                                        disconnect()
                                    }
                                }
                                // 可以添加其他命令的响应处理
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing custom data response", e)
                    resultMap["parseError"] = e.message ?: "Unknown error"
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                if (!isComplete) {
                    isComplete = true
                    resultMap["error"] = "Communication error: $errCode"
                    resultMap["success"] = "false"
                    resultMap["completedAt"] = System.currentTimeMillis().toString()
                    callback?.invoke(resultMap)
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }
        })
    }

//endregion

//---------------A厂自定义---------
    /**
     * 重启设备
     *
     * @param callback 结果回调
     */
    fun restartDevice(callback: ((Boolean) -> Unit)? = null) {
        // 发送重启指令 8:
        val restartCmd = "8:"
        configureCallback = callback
        postCustomData(restartCmd.toByteArray())
    }

    /**
     * 获取设备UID
     *
     * @param onResult 结果回调，传递UID字符串
     */
    fun getDeviceUID(onResult: (String?) -> Unit) {
        // 发送获取UID指令 12:
        val uidCmd = "12:"

        // 这里假设设备会通过自定义数据返回UID
        // 实际实现可能需要在BlufiCallback中处理返回数据
        postCustomData(uidCmd.toByteArray())

        // 注意：这里需要在接收到设备返回数据后回调
        // 此处仅为示例，实际实现应在onReceiveCustomData中处理
    }
}