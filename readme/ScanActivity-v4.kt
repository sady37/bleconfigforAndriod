/**
 * File: ScanActivity.kt
 * 统一的设备扫描界面，支持 radarQL/sleepace/自定义过滤三种扫描模式
 */
package com.wisefido

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.wisefido.ConfigModels.DeviceType
import com.wisefido.ConfigModels.DeviceInfo
import com.wisefido.ConfigStorage

import com.espressif.espblufi.RadarBleManager
import com.bleconfig.sleepace.SleePaceBleManager

import com.sleepace.sdk.interfs.IResultCallback
import com.sleepace.sdk.manager.CallbackData
import com.sleepace.sdk.constant.StatusCode
import com.sleepace.sdk.domain.BleDevice


class ScanActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScanActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_ENABLE_BT = 101
        const val EXTRA_DEVICE = "extra_device"  // 统一的设备返回键
    }

    // 视图组件
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioRadarQL: RadioButton
    private lateinit var radioSleepace: RadioButton
    private lateinit var radioEsp: RadioButton
    private lateinit var inputFilter: EditText
    private lateinit var rvDevices: RecyclerView
    private lateinit var btnBack: ImageButton

    // 数据存储
    private lateinit var configStorage: ConfigStorage

    // 设备列表 - 仅用于显示
    private val deviceList = mutableListOf<DeviceInfo>()

    // 当前扫描的厂家模块
    private var currentScanModule: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        // 1. 先检查所有必需的权限
        if (!checkAndRequestPermissions()) {
            return
        }

        // 2. 检查蓝牙是否开启
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "This device doesn't support Bluetooth", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            // 请求开启蓝牙
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
            return
        }

        // 3. 初始化存储和视图
        configStorage = ConfigStorage(this)
        initViews()
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()

        // 基础蓝牙权限
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Android 12+ 蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // 请求所需权限
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }

        return true
    }

    private fun initViews() {
        // 绑定视图组件
        radioGroup = findViewById(R.id.radio_device_type)
        radioRadarQL = findViewById(R.id.radio_radarQL)
        radioSleepace = findViewById(R.id.radio_sleepace)
        radioEsp = findViewById(R.id.radio_esp)
        inputFilter = findViewById(R.id.input_filter)
        rvDevices = findViewById(R.id.rv_devices)
        btnBack = findViewById(R.id.btn_back)

        // 设置返回按钮
        btnBack.setOnClickListener { finish() }

        // 初始化过滤输入框
        inputFilter.hint = "Filter device name (optional)"
        inputFilter.visibility = View.GONE  // 默认隐藏，选择ESP时显示

// ... 下一部分继续
// 设置RecyclerView
        rvDevices.layoutManager = LinearLayoutManager(this)
        val adapter = DeviceAdapter()
        rvDevices.adapter = adapter

        // 设置扫描模式选择
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            // 清空当前设备列表
            deviceList.clear()
            rvDevices.adapter?.notifyDataSetChanged()

            // 停止当前扫描
            stopCurrentScan()

            when (checkedId) {
                R.id.radio_radarQL -> {
                    currentScanModule = "RadarQL"
                    inputFilter.visibility = View.GONE
                    startRadarQLScan()
                }
                R.id.radio_sleepace -> {
                    currentScanModule = "Sleepace"
                    inputFilter.visibility = View.GONE
                    startSleepaceScan()
                }
                R.id.radio_esp -> {
                    currentScanModule = "ESP"
                    inputFilter.visibility = View.VISIBLE
                    startEspScan(inputFilter.text.toString().trim())
                }
            }
        }

        // 监听过滤器输入变化
        inputFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (currentScanModule == "ESP") {
                    // 重新开始扫描，应用新的过滤器
                    stopCurrentScan()
                    startEspScan(s.toString().trim())
                }
            }
        })
    }

    // ===== 扫描实现 =====
    private fun startRadarQLScan() {
        val radarManager = RadarBleManager.getInstance(this)
        radarManager.setScanCallback { result ->
            runOnUiThread {
                processRadarQLDevice(result)
            }
        }
        radarManager.startScan()
    }

    private fun processRadarQLDevice(result: ScanResult) {
        val deviceName = result.device.name ?: return
        if (!deviceName.startsWith("RadarQL")) return

        val device = DeviceInfo(
            type = DeviceType.RADAR,
            name = deviceName,
            macAddress = result.device.address,
            rssi = result.rssi,
            scanTime = System.currentTimeMillis()
        )
        updateDeviceList(device)
    }

    private fun startSleepaceScan() {
        val sleepaceManager = SleePaceBleManager.getInstance(this)
        sleepaceManager.startScan { callbackData ->
            if (callbackData.status == StatusCode.SUCCESS && callbackData.data is BleDevice) {
                val bleDevice = callbackData.data as BleDevice
                runOnUiThread {
                    processSleepaceDevice(bleDevice)
                }
            }
        }
    }

    private fun processSleepaceDevice(bleDevice: BleDevice) {
        val device = DeviceInfo(
            type = DeviceType.SLEEPBOARD,
            name = bleDevice.deviceName ?: "",
            macAddress = bleDevice.address,
            rssi = bleDevice.rssi ?: 0,
            scanTime = System.currentTimeMillis()
        )
        updateDeviceList(device)
    }

    private fun startEspScan(filter: String) {
        val radarManager = RadarBleManager.getInstance(this)
        radarManager.setScanCallback { result ->
            val deviceName = result.device.name ?: return@setScanCallback
            if (filter.isNotEmpty() && !deviceName.contains(filter, ignoreCase = true)) {
                return@setScanCallback
            }
            runOnUiThread {
                val device = DeviceInfo(
                    type = DeviceType.ESP,
                    name = deviceName,
                    macAddress = result.device.address,
                    rssi = result.rssi,
                    scanTime = System.currentTimeMillis()
                )
                updateDeviceList(device)
            }
        }
        radarManager.startScan()
    }

    private fun updateDeviceList(newDevice: DeviceInfo) {
        val existingIndex = deviceList.indexOfFirst { it.macAddress == newDevice.macAddress }
        if (existingIndex >= 0) {
            deviceList[existingIndex] = newDevice
            rvDevices.adapter?.notifyItemChanged(existingIndex)
        } else {
            deviceList.add(newDevice)
            rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
        }
    }

    private fun stopCurrentScan() {
        when (currentScanModule) {
            "RadarQL" -> RadarBleManager.getInstance(this).stopScan()
            "Sleepace" -> SleePaceBleManager.getInstance(this).stopScan()
            "ESP" -> RadarBleManager.getInstance(this).stopScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                recreate()
            } else {
                Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                recreate()
            } else {
                Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopCurrentScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCurrentScan()
        when (currentScanModule) {
            "RadarQL" -> RadarBleManager.getInstance(this).release()
            "Sleepace" -> SleePaceBleManager.getInstance(this).release()
            "ESP" -> RadarBleManager.getInstance(this).release()
        }
    }
}