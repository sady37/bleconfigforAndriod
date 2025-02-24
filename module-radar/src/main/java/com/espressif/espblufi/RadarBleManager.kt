/**
 * File: RadarBleManager.kt
 * Path: module-radar/src/main/java/com/espressif/espblufi/RadarBleManager.kt
 *
 * A厂(Radar)蓝牙管理类，封装 BlufiClient 实现
 * */
package com.espressif.espblufi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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
import android.util.Log

//common data type
import com.common.DeviceInfo
import com.common.Productor
import com.common.FilterType
import com.common.ServerConfig
import com.common.WifiConfig

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
    private val appContext: Context = context.applicationContext
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

    /**
     * 设置扫描回调
     */
    fun setScanCallback(callback: (DeviceInfo) -> Unit) {
        scanCallback = callback
    }

    /**
     * 开始扫描
     * @param filterPrefix 过滤值，null 或空值时不过滤
     * @param filterType 过滤类型，默认为设备名称过滤
     */
    fun startScan(filterPrefix: String?, filterType: FilterType = FilterType.DEVICE_NAME) {
        if (isScanning) return
        Log.d(TAG, "RadarBleManager startScan with filterPrefix: '$filterPrefix', filterType: $filterType")
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
                //Log.d(TAG, "Started scanning with filter: ${filterPrefix ?: "none"}, type: $filterType")
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
                //Log.d(TAG, "Checking device: ${result.device.name ?: "null"} against prefix: $prefix")
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
                                .contains(prefix, ignoreCase = true)) {
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

            //Log.d(TAG, "Device passed filter: ${result.device.name}")
            mainHandler.post {
                scanCallback?.invoke(deviceInfo)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false
        }
    }

    /**
     * 连接设备
     */
    fun connect(device: BluetoothDevice) {
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

    fun connectByAddress(context: Context, macAddress: String): Boolean {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(macAddress)
            connect(device)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device: $macAddress", e)
            return false
        }
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
            override fun onGattPrepared(client: BlufiClient,
                                        gatt: BluetoothGatt,
                                        service: BluetoothGattService?,
                                        writeChar: BluetoothGattCharacteristic?,
                                        notifyChar: BluetoothGattCharacteristic?) {
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

            override fun onDeviceStatusResponse(client: BlufiClient, status: Int, response: BlufiStatusResponse) {
                Log.d(TAG, "Device status: $status, response: $response")
            }

            override fun onDeviceVersionResponse(client: BlufiClient, status: Int, response: BlufiVersionResponse) {
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

    /**
     * 查询RadarQL设备状态
     * 依次查询：UID -> WiFi Status -> Server Status
     */

    fun queryDeviceStatus(deviceInfo: DeviceInfo, callback: ((Map<String, String>) -> Unit)? = null) {
        Log.d(TAG, "Start query device status for: ${deviceInfo.deviceId}, MAC: ${deviceInfo.macAddress}")

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
            override fun onGattPrepared(client: BlufiClient,
                                        gatt: BluetoothGatt,
                                        service: BluetoothGattService?,
                                        writeChar: BluetoothGattCharacteristic?,
                                        notifyChar: BluetoothGattCharacteristic?) {
                Log.d(TAG, "onGattPrepared: service=${service != null}, write=${writeChar != null}, notify=${notifyChar != null}")

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

            override fun onDeviceStatusResponse(client: BlufiClient, status: Int, response: BlufiStatusResponse) {
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
                    statusMap["wifiOpMode"] = when(response.opMode) {
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
                                    Log.e(TAG, "Failed to parse UID response, status=$status, parts.size=${parts.size}")
                                    statusMap["uidError"] = "Failed to parse UID response"
                                }

                                // 检查查询状态
                                Log.d(TAG, "Query status: hasWifiStatus=$hasWifiStatus, hasUID=$hasUID")
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
                                    Log.d(TAG, "UID received but no WiFi status yet, requesting device status")
                                    client.requestDeviceStatus()
                                }
                            }
                            62 -> { // WiFi 状态响应 (如果使用自定义命令查询WiFi)
                                Log.d(TAG, "Identified as WiFi status response (command=62)")
                                Log.d(TAG, "WiFi status from custom command: ${parts.joinToString(":")}")

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
                                Log.w(TAG, "Unknown custom data command: $command, response: $stringData")
                                statusMap["unknownResponse"] = stringData
                            }
                        }
                    } else {
                        Log.w(TAG, "Custom data doesn't contain ':' separator, can't parse as command")
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

    /**
     * 配置设备 WiFi 和服务器
     *
     * @param deviceInfo 设备信息
     * @param wifiConfig WiFi配置信息，如果为null则跳过WiFi配置
     * @param serverConfig 服务器配置信息，如果为null则跳过服务器配置
     * @param callback 配置结果回调
     */
    fun configureDevice(
        deviceInfo: DeviceInfo,
        wifiConfig: WifiConfig?,
        serverConfig: ServerConfig?,
        callback: ((Boolean, String) -> Unit)? = null
    ) {
        Log.d(TAG, "Start configuring device: ${deviceInfo.deviceId}, MAC: ${deviceInfo.macAddress}")
        Log.d(TAG, "WiFi config: ${wifiConfig?.ssid}, Server config: ${serverConfig?.serverAddress}:${serverConfig?.port}")

        // 如果两项都为null，直接返回
        if (wifiConfig == null && serverConfig == null) {
            Log.d(TAG, "Nothing to configure, both WiFi and server configs are null")
            callback?.invoke(false, "Nothing to configure")
            return
        }

        // 直接使用 macAddress 创建 BluetoothDevice
        val device = bluetoothAdapter?.getRemoteDevice(deviceInfo.macAddress) ?: run {
            Log.e(TAG, "Cannot create device from MAC: ${deviceInfo.macAddress}")
            callback?.invoke(false, "Invalid device address")
            return
        }

        // 配置状态追踪
        var isConfiguring = true
        var wifiConfigured = wifiConfig == null // 如果为null则视为已配置
        var serverConfigured = serverConfig == null // 如果为null则视为已配置

        // 配置超时
        val configTimeoutRunnable = Runnable {
            Log.e(TAG, "Configuration timeout after 20 seconds")
            if (isConfiguring) {
                isConfiguring = false
                callback?.invoke(false, "Configuration timeout")
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
                Log.d(TAG, "onGattPrepared: service=${service != null}, write=${writeChar != null}, notify=${notifyChar != null}")

                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    if (isConfiguring) {
                        isConfiguring = false
                        callback?.invoke(false, "Service discovery failed")
                        disconnect()
                    }
                    return
                }

                // 设置超时
                mainHandler.postDelayed(configTimeoutRunnable, 20000)

                // 先进行安全协商
                Log.d(TAG, "Starting security negotiation")
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                Log.d(TAG, "Security negotiation result: $status")

                if (status == STATUS_SUCCESS) {
                    // 协商成功，开始配置
                    startConfiguration(client)
                } else {
                    Log.e(TAG, "Security negotiation failed with status: $status")
                    if (isConfiguring) {
                        isConfiguring = false
                        callback?.invoke(false, "Security negotiation failed: $status")
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }
            }

            private fun startConfiguration(client: BlufiClient) {
                if (wifiConfig != null) {
                    // 配置 WiFi
                    Log.d(TAG, "Configuring WiFi: ${wifiConfig.ssid}")

                    val params = BlufiConfigureParams()
                    params.opMode = 1 // STA模式
                    params.setStaSSIDBytes(wifiConfig.ssid.toByteArray())
                    params.staPassword = wifiConfig.password

                    client.configure(params)
                } else if (serverConfig != null) {
                    // 如果没有WiFi需要配置，直接配置服务器
                    configureServer(client)
                }
            }

            private fun configureServer(client: BlufiClient) {
                if (serverConfig != null) {
                    Log.d(TAG, "Configuring server: ${serverConfig.serverAddress}:${serverConfig.port}")

                    // 发送自定义命令配置服务器
                    // 格式: "1:服务器IP" 和 "2:port"
                    val serverCmd = "1:${serverConfig.serverAddress}".toByteArray()
                    client.postCustomData(serverCmd)
                } else {
                    // 服务器配置为null，视为配置完成
                    completeConfiguration(true, "Configuration complete")
                }
            }

            override fun onPostConfigureParams(client: BlufiClient, status: Int) {
                Log.d(TAG, "WiFi configuration result: $status")

                if (status == STATUS_SUCCESS) {
                    wifiConfigured = true
                    Log.d(TAG, "WiFi configured successfully")

                    // WiFi配置成功后，配置服务器
                    if (!serverConfigured) {
                        configureServer(client)
                    } else {
                        // 全部配置完成
                        completeConfiguration(true, "Configuration complete")
                    }
                } else {
                    Log.e(TAG, "WiFi configuration failed: $status")
                    completeConfiguration(false, "WiFi configuration failed: $status")
                }
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                try {
                    val response = String(data)
                    Log.d(TAG, "Received custom data: $response, status: $status")

                    val parts = response.split(":")
                    val command = parts.getOrNull(0)?.toIntOrNull()

                    when (command) {
                        1 -> { // 服务器IP配置响应
                            if (status == STATUS_SUCCESS && parts.size >= 2 && parts[1] == "0") {
                                // 继续配置端口
                                Log.d(TAG, "Server IP configured, configuring port")
                                val portCmd = "2:${serverConfig?.port}".toByteArray()
                                client.postCustomData(portCmd)
                            } else {
                                Log.e(TAG, "Server IP configuration failed")
                                completeConfiguration(false, "Server IP configuration failed")
                            }
                        }
                        2 -> { // 端口配置响应
                            if (status == STATUS_SUCCESS && parts.size >= 2 && parts[1] == "0") {
                                Log.d(TAG, "Server port configured successfully")
                                serverConfigured = true

                                // 如果WiFi也配置完成，则完成整个配置过程
                                if (wifiConfigured) {
                                    completeConfiguration(true, "Configuration complete")
                                }
                            } else {
                                Log.e(TAG, "Server port configuration failed")
                                completeConfiguration(false, "Server port configuration failed")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing custom data", e)
                    completeConfiguration(false, "Error parsing response: ${e.message}")
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                Log.e(TAG, "BluFi error: $errCode")
                completeConfiguration(false, "Communication error: $errCode")
            }

            private fun completeConfiguration(success: Boolean, message: String) {
                if (isConfiguring) {
                    isConfiguring = false
                    Log.d(TAG, "Configuration completed: $success, message: $message")
                    callback?.invoke(success, message)
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }
        })
    }

}