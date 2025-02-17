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
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.espressif.espblufi.constants.BlufiConstants
import com.espressif.espblufi.params.BlufiConfigureParams
import com.espressif.espblufi.response.BlufiStatusResponse
import com.espressif.espblufi.response.BlufiVersionResponse

/**
 * A厂雷达设备蓝牙管理类
 * - 扫描: 使用系统原生扫描，支持 TSBLU 前缀过滤
 * - 通信: 封装 BlufiClient，负责连接和配网
 */
@SuppressLint("MissingPermission")
class RadarBleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RadarBleManager"
        private const val DEVICE_PREFIX = "TSBLU"

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

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var blufiClient: BlufiClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScanning = false
    private var useFilter = true
    private var configureCallback: ((Boolean) -> Unit)? = null

    // 扫描回调
    private var scanCallback: ((ScanResult) -> Unit)? = null

    /**
     * 设置扫描回调
     */
    fun setScanCallback(callback: (ScanResult) -> Unit) {
        scanCallback = callback
    }

    /**
     * 设置是否启用 TSBLU 过滤
     */
    fun enableFilter(enable: Boolean) {
        useFilter = enable
    }

    /**
     * 开始扫描
     */
    fun startScan() {
        if (isScanning) return

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = true

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, leScanCallback)
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

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = false
            try {
                scanner.stopScan(leScanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Stop scan failed: ${e.message}")
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: return

            // TSBLU 前缀过滤
            if (!useFilter || deviceName.startsWith(DEVICE_PREFIX)) {
                mainHandler.post {
                    scanCallback?.invoke(result)
                }
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