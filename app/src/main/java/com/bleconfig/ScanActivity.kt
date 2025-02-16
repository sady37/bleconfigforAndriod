// File: app/src/main/java/com/bleconfig/ScanActivity.kt
// 设备扫描页面
// 功能：
// 1. 提供 Radar:TSBLU（可修改）和 SleepBoard 两个选项
// 2. 如果 TSBLU 为空，则等同于 All（不过滤）

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

import android.widget.ImageButton // 添加 ImageButton 的导入


import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanActivity : AppCompatActivity() {

    // 视图组件
    private lateinit var radioGroup: RadioGroup
    private lateinit var inputTSBLU: EditText
    private lateinit var radioRadar: RadioButton
    private lateinit var radioSleep: RadioButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var btnBack: ImageButton // 新增返回按钮

    // 数据存储
    private lateinit var configStorage: ConfigStorage

    // 设备列表
    private val deviceList = mutableListOf<DeviceInfo>()

    // 当前扫描的厂家模块（A厂或B厂）
    private var currentScanModule: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

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
        btnBack = findViewById(R.id.btn_back) // 绑定返回按钮

        // 设置返回按钮的点击事件
        btnBack.setOnClickListener {
            onBackButtonClick()
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
        // 停止当前的扫描（如果有）
        stopCurrentScan()

        // 设置当前扫描模块为 A厂
        currentScanModule = "A厂"

        // 启动 A厂模块的扫描
        val intent = Intent("com.radar.SCAN_ACTION").apply {
            if (filterTSBLU != null) {
                putExtra("filter_tsblu", filterTSBLU) // 传递 TSBLU 过滤条件
            }
        }
        startActivityForResult(intent, REQUEST_RADAR_SCAN)
    }

    // 调用 B 厂模块进行扫描（SleepBoard）
    private fun startSleepScan() {
        // 停止当前的扫描（如果有）
        stopCurrentScan()

        // 设置当前扫描模块为 B厂
        currentScanModule = "B厂"

        // 启动 B厂模块的扫描
        val intent = Intent("com.sleepboard.SCAN_ACTION")
        startActivityForResult(intent, REQUEST_SLEEP_SCAN)
    }

    // 停止当前的扫描
    private fun stopCurrentScan() {
        when (currentScanModule) {
            "A厂" -> {
                // 停止 A厂模块的扫描
                val intent = Intent("com.radar.STOP_SCAN_ACTION")
                sendBroadcast(intent)
            }
            "B厂" -> {
                // 停止 B厂模块的扫描
                val intent = Intent("com.sleepboard.STOP_SCAN_ACTION")
                sendBroadcast(intent)
            }
        }
    }

    // 处理返回按钮点击事件
    private fun onBackButtonClick() {
        // 返回到上一个界面
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_RADAR_SCAN -> handleRadarResult(data) // 处理 A 厂模块的扫描结果
                REQUEST_SLEEP_SCAN -> handleSleepResult(data) // 处理 B 厂模块的扫描结果
            }
        }
    }

    // 处理 A 厂模块的扫描结果
    private fun handleRadarResult(data: Intent?) {
        data?.let {
            val deviceType = it.getStringExtra("device_type") ?: "RADAR"
            val deviceId = it.getStringExtra("device_id") ?: ""
            val deviceMac = it.getStringExtra("device_mac") ?: ""

            // 添加到设备列表
            deviceList.add(DeviceInfo(DeviceType.valueOf(deviceType), deviceId, deviceMac, -60))
            (rvDevices.adapter as? DeviceAdapter)?.notifyItemInserted(deviceList.size - 1)
        }
    }

    // 处理 B 厂模块的扫描结果
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

    // 内部类：DeviceAdapter
    private class DeviceAdapter(
        private val deviceList: List<DeviceInfo>,
        private val configuredDevices: List<DeviceHistory>,
        private val onDeviceClick: (DeviceInfo) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return DeviceViewHolder(view, onDeviceClick)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            holder.bind(deviceList[position], configuredDevices)
        }

        override fun getItemCount(): Int {
            return deviceList.size
        }

        // 内部类：DeviceViewHolder
        class DeviceViewHolder(
            itemView: View,
            private val onDeviceClick: (DeviceInfo) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val tvDeviceId: TextView = itemView.findViewById(R.id.tv_device_id)
            private val tvMacConfigTime: TextView = itemView.findViewById(R.id.tv_mac_config_time)
            private val tvRssi: TextView = itemView.findViewById(R.id.tv_rssi)

            fun bind(device: DeviceInfo, configuredDevices: List<DeviceHistory>) {
                // 显示 DeviceID
                tvDeviceId.text = device.name

                // 显示 MAC/config time
                val configTime = configuredDevices.find { it.macAddress == device.macAddress }?.configTime
                val macConfigTime = if (configTime != null) {
                    "${device.macAddress} / ${formatConfigTime(configTime)}"
                } else {
                    device.macAddress
                }
                tvMacConfigTime.text = macConfigTime

                // 显示 RSI
                tvRssi.text = "RSI: ${device.rssi}"

                // 设置点击事件
                itemView.setOnClickListener {
                    onDeviceClick(device)
                }
            }

            private fun formatConfigTime(configTime: Long): String {
                // 格式化配置时间（例如：yyyy-MM-dd HH:mm:ss）
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                return dateFormat.format(Date(configTime))
            }
        }
    }

    companion object {
        private const val REQUEST_RADAR_SCAN = 100 // A 厂模块扫描请求码
        private const val REQUEST_SLEEP_SCAN = 101 // B 厂模块扫描请求码

        // 定义常量，用于传递设备信息
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_MAC = "device_mac"
    }

}