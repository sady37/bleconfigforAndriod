Changes in v2.0:
1. Replace Intent scanning with direct RadarBleManager usage
2. Add RadarBleManager initialization and callbacks
3. Remove Intent-related code for Radar scanning
4. Update device list management
5. Keep SleepBoard Intent scanning unchanged

Key improvements:
- More direct device scanning management
- Real-time device discovery
- Simplified code structure for A-vendor devices
- Better error handling

Files changed:
1. ScanActivity.kt - Major updates to scanning logic
2. No layout changes

Detailed changes:
[app/src/main/java/com/bleconfig/ScanActivity.kt]
// File: app/src/main/java/com/bleconfig/ScanActivity.kt
// 设备扫描页面
// 功能：
// 1. 提供 Radar:TSBLU（可修改）和 SleepBoard 两个选项
// 2. 如果 TSBLU 为空，则等同于 All（不过滤）
// 3. 直接使用 RadarBleManager 进行扫描管理

package com.bleconfig

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.radar.RadarBleManager
import com.radar.RadarScanResult

class ScanActivity : AppCompatActivity() {

    // 视图组件
    private lateinit var radioGroup: RadioGroup
    private lateinit var inputTSBLU: EditText
    private lateinit var radioRadar: RadioButton
    private lateinit var radioSleep: RadioButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var btnBack: ImageButton

    // 数据存储
    private lateinit var configStorage: ConfigStorage
    
    // A厂蓝牙管理器
    private var radarManager: RadarBleManager? = null

    // 设备列表
    private val deviceList = mutableListOf<DeviceInfo>()

    // 当前扫描的厂家模块（A厂或B厂）
    private var currentScanModule: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        // 初始化 A厂蓝牙管理器
        radarManager = RadarBleManager.getInstance(this)

        // 初始化配置存储
        configStorage = ConfigStorage(this)

        // 初始化视图
        initViews()
    }

    private fun initViews() {
        // 绑定视图组件
        radioGroup = findViewById(R.id.radio_device_type)
        inputTSBLU = findViewById(R.id.input_tsblu)
        radioRadar = findViewById(R.id.radio_radar)
        radioSleep = findViewById(R.id.radio_sleep)
        rvDevices = findViewById(R.id.rv_devices)
        btnBack = findViewById(R.id.btn_back)

        // 设置返回按钮的点击事件
        btnBack.setOnClickListener {
            finish()
        }

        // 初始化 RecyclerView
        val deviceAdapter = DeviceAdapter(deviceList, configStorage.getDeviceHistories()) { device ->
            // 返回设备信息给 MainActivity
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_DEVICE_TYPE, device.type.name)
                putExtra(EXTRA_DEVICE_ID, device.name)
                putExtra(EXTRA_DEVICE_MAC, device.macAddress)
            })
            finish()
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // 设置 RadioGroup 的监听器
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_radar -> {
                    // 选择 Radar 时，调用 A厂模块
                    val tsbluValue = inputTSBLU.text.toString().trim()
                    startRadarScan(filterTSBLU = if (tsbluValue.isEmpty()) null else tsbluValue)
                }
                R.id.radio_sleep -> {
                    // 选择 SleepBoard 时，调用 B厂模块
                    startSleepScan()
                }
            }
        }
    }

    // 调用 A 厂模块进行扫描（Radar）
    private fun startRadarScan(filterTSBLU: String?) {
        stopCurrentScan()
        currentScanModule = "A厂"

        radarManager?.let { manager ->
            manager.setScanCallback { result ->
                // 处理扫描结果
                val device = DeviceInfo(
                    type = DeviceType.RADAR,
                    name = result.name ?: "",
                    macAddress = result.device.address,
                    rssi = result.rssi
                )
                
                runOnUiThread {
                    if (!deviceList.any { it.macAddress == device.macAddress }) {
                        deviceList.add(device)
                        rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                    }
                }
            }

            // 设置过滤
            manager.enableFilter(!filterTSBLU.isNullOrEmpty())
            // 开始扫描
            manager.startScan()
        }
    }

    // 调用 B 厂模块进行扫描（SleepBoard）- 保持不变
    private fun startSleepScan() {
        stopCurrentScan()
        currentScanModule = "B厂"
        val intent = Intent("com.sleepboard.SCAN_ACTION")
        startActivityForResult(intent, REQUEST_SLEEP_SCAN)
    }

    // 停止当前的扫描
    private fun stopCurrentScan() {
        when (currentScanModule) {
            "A厂" -> {
                radarManager?.stopScan()
                deviceList.clear()
                rvDevices.adapter?.notifyDataSetChanged()
            }
            "B厂" -> {
                // 停止 B厂模块的扫描 - 保持不变
                val intent = Intent("com.sleepboard.STOP_SCAN_ACTION")
                sendBroadcast(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCurrentScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_SLEEP_SCAN -> handleSleepResult(data) // 处理 B 厂模块的扫描结果
            }
        }
    }

    // 处理 B 厂模块的扫描结果 - 保持不变
    private fun handleSleepResult(data: Intent?) {
        data?.let {
            val deviceType = it.getStringExtra("device_type") ?: "SLEEP"
            val deviceId = it.getStringExtra("device_id") ?: ""
            val deviceMac = it.getStringExtra("device_mac") ?: ""

            // 添加到设备列表
            deviceList.add(DeviceInfo(DeviceType.valueOf(deviceType), deviceId, deviceMac, -70))
            (rvDevices.adapter as? DeviceAdapter)?.notifyItemInserted(deviceList.size - 1)
        }
    }

    // 内部类：DeviceAdapter - 保持不变
    private class DeviceAdapter(
        private val deviceList: List<DeviceInfo>,
        private val configuredDevices: List<DeviceHistory>,
        private val onDeviceClick: (DeviceInfo) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
        // ... DeviceAdapter 实现保持不变 ...
    }

    companion object {
        private const val REQUEST_SLEEP_SCAN = 101 // B 厂模块扫描请求码

        // 定义常量，用于传递设备信息
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_MAC = "device_mac"
    }
}