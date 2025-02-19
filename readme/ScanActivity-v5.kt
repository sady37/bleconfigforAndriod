/**
 * File: RadarBleManager.kt
 * Path: module-radar/src/main/java/com/bleconfig/radar/RadarBleManager.kt
 *
 * A厂(Radar)蓝牙管理类，封装 BlufiClient 实现
 */
package com.espressif.espblufi

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
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.espressif.espblufi.constants.BlufiConstants
import com.espressif.espblufi.params.BlufiConfigureParams
import com.espressif.espblufi.response.BlufiStatusResponse
import com.espressif.espblufi.response.BlufiVersionResponse

/**
 * File: RadarBleManager.kt
 * Path: module-radar/src/main/java/com/bleconfig/radar/RadarBleManager.kt
 * A厂蓝牙管理类，基于原生蓝牙扫描和 ESP BlufiClient 实现
 *  通信: 封装 BlufiClient，负责连接和配网
 * 修改说明：
 * 1. 修改扫描过滤机制，不使用原生 ScanFilter，改为在回调中手动过滤前缀
 * 2. 简化扫描接口，统一处理设备名称过滤
 * 3. 增加详细日志和错误处理
 */

@SuppressLint("MissingPermission")
class RadarBleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RadarBleManager"

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

    // 系统服务
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var blufiClient: BlufiClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 扫描状态
    private var isScanning = false
    private var currentFilter: String? = null
    private var scanCallback: ((ScanResult) -> Unit)? = null

    // 配置回调
    private var configureCallback: ((Boolean) -> Unit)? = null

    /**
     * 设置扫描结果回调
     * @param callback 扫描结果回调，在主线程中调用
     */
    fun setScanCallback(callback: (ScanResult) -> Unit) {
        scanCallback = callback
    }

    /**
     * 开始蓝牙扫描，支持设备名称前缀过滤
     * @param filter 设备名称前缀，null 表示不过滤
     * 注意：
     * 1. 必须具有 BLUETOOTH_SCAN 权限
     * 2. 蓝牙必须已开启
     * 3. 扫描结果通过 scanCallback 返回
     */
    fun startScan(filter: String? = null) {
        // 避免重复扫描
        if (isScanning) {
            Log.w(TAG, "Scan already in progress")
            return
        }

        // 检查蓝牙适配器
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Bluetooth LE scanner not available")
            return
        }

        isScanning = true
        currentFilter = filter
        
        // 配置扫描参数
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // 使用低延迟模式
            .build()

        try {
            // 不使用系统过滤器，在回调中手动过滤
            scanner.startScan(null, settings, leScanCallback)
            Log.d(TAG, "Started BLE scan with manual filtering for prefix: ${filter ?: "none"}")
        } catch (e: Exception) {
            isScanning = false
            Log.e(TAG, "Failed to start scan", e)
        }
    }

    /**
     * 原生蓝牙扫描回调
     * 注意：回调发生在蓝牙线程，需要切换到主线程处理
     */
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 获取设备名称
            val deviceName = result.device.name ?: ""
            
            // 手动进行前缀过滤
            if (currentFilter == null || 
                currentFilter!!.isEmpty() || 
                deviceName.startsWith(currentFilter!!)) {
                
                // 将过滤后的扫描结果回调到主线程
                mainHandler.post {
                    scanCallback?.invoke(result)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false

            // 错误码含义：
            // SCAN_FAILED_ALREADY_STARTED = 1
            // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
            // SCAN_FAILED_INTERNAL_ERROR = 3
            // SCAN_FAILED_FEATURE_UNSUPPORTED = 4
            // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        }
    }

    /**
     * 停止扫描
     * 在不需要扫描时应主动调用，以节省电量
     */
    fun stopScan() {
        if (!isScanning) {
            return
        }

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = false
            currentFilter = null
            try {
                scanner.stopScan(leScanCallback)
                Log.d(TAG, "Stopped BLE scan")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop scan", e)
            }
        }
    }
    
    // 其余代码保持不变...
    
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
        instance = null
    }
}