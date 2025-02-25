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
            connect(device)
        } else {
            Log.e(TAG, "Failed to get device with MAC: $deviceMacAddress")
            // 处理错误
        }
    }

    private fun connect(device: BluetoothDevice) {
        disconnect()

        BlufiClient(context, device).also { client ->
            blufiClient = client

            // 设置 GATT 回调
            client.setGattCallback(createGattCallback())
            client.setBlufiCallback(createBlufiCallback())

            // 设置超时
            client.setGattWriteTimeout(BlufiConstants.GATT_WRITE_TIMEOUT)

            // 开始连接
            client.connect()
        }
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
        blufiClient?.close()
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
        instance = null
    }

    private fun createGattCallback(): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            gatt.discoverServices()
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            disconnect()
                        }
                    }
                } else {
                    disconnect()
                }
            }
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
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    disconnect()
                    return
                }
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                Log.d(TAG, "Security negotiation result: $status")
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
                when (errCode) {
                    CODE_GATT_WRITE_TIMEOUT -> {
                        disconnect()
                    }
                }
            }
        }
    }

    //endregion

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
                deviceName = result.device.name ?: "",
                deviceId = result.device.name ?: "",
                macAddress = result.device.address,
                rssi = result.rssi,
                originalDevice = result
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
            Log.e(TAG, "Query timeout after 15 seconds")
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
                mainHandler.postDelayed(queryTimeoutRunnable, 15000)

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
                    Log.d(TAG, "----- Basic Information -----")
                    Log.d(TAG, "Operation mode: ${response.opMode}")
                    Log.d(TAG, "STA connection status: ${response.staConnectionStatus}")
                    Log.d(TAG, "SoftAP connection count: ${response.softAPConnectionCount}")

                    Log.d(TAG, "----- STA Information -----")
                    Log.d(TAG, "STA SSID: ${response.staSSID}")
                    Log.d(TAG, "STA BSSID: ${response.staBSSID}")
                    Log.d(TAG, "STA Password: ${response.staPassword}")

                    Log.d(TAG, "----- SoftAP Information -----")
                    Log.d(TAG, "SoftAP SSID: ${response.softAPSSID}")
                    Log.d(TAG, "SoftAP Security: ${response.softAPSecurity}")
                    Log.d(TAG, "SoftAP Password: ${response.softAPPassword}")
                    Log.d(TAG, "SoftAP Channel: ${response.softAPChannel}")
                    Log.d(TAG, "SoftAP Max Connection Count: ${response.softAPMaxConnectionCount}")

                    // 尝试通过反射获取所有字段
                    try {
                        Log.d(TAG, "----- All Fields via Reflection -----")
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

                    // 记录完整对象的文本表示
                    Log.d(TAG, "----- Complete Object toString -----")
                    Log.d(TAG, response.toString())

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
                mainHandler.postDelayed(configTimeoutRunnable, 25000)

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
                        }, 2000)
                    }
                }, 2000)
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
                        }, 2000)
                    }
                }, 2000)
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
                }, 5000)
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