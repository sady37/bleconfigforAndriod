/**
 * File: ScanActivity.kt
 * Package: com.wisefido
 *
 * 目录结构:
 * 1. 类定义和属性声明
 * 2. Activity生命周期函数
 * 3. 权限检查相关
 * 4. UI初始化和处理
 * 5. 设备扫描实现
 *    - A厂扫描实现
 *    - B厂扫描实现
 * 6. 结果处理
 * 7. 适配器实现
 */

package com.wisefido

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.espressif.espblufi.RadarBleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫描界面
 * 功能：
 * 1. 统一管理A/B厂设备扫描
 * 2. 提供设备选择界面
 * 3. 处理扫描结果
 */
class ScanActivity : AppCompatActivity() {

    // region 属性定义
    private lateinit var radioGroup: RadioGroup
    private lateinit var inputTSBLU: EditText
    private lateinit var radioRadar: RadioButton
    private lateinit var radioSleep: RadioButton
    private lateinit var rvDevices: RecyclerView
    private lateinit var btnBack: ImageButton

    private lateinit var configStorage: ConfigStorage
    private val deviceList = mutableListOf<DeviceInfo>()
    private var currentScanModule: String? = null

    // 注册蓝牙开启请求
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            recreate()
        }
    }

    // 注册B厂扫描结果
    private val sleepaceScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                // 通过SearchBleDeviceActivity获取扫描结果
                val deviceType = data.getStringExtra("device_type")
                val deviceName = data.getStringExtra("device_name")
                val deviceMac = data.getStringExtra("device_mac")
                val deviceId = data.getStringExtra("device_id")

                if (deviceMac != null && deviceName != null) {
                    val device = DeviceInfo(
                        type = DeviceType.SLEEPBOARD,
                        name = deviceName,
                        macAddress = deviceMac,
                        rssi = 0,
                        deviceId = deviceId,
                        firmwareVersion = null
                    )

                    if (!deviceList.any { it.macAddress == device.macAddress }) {
                        deviceList.add(device)
                        rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                    }
                }
            }
        }
    }
    // endregion

    // region Activity生命周期
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        if (!checkAndRequestPermissions()) {
            return
        }

        if (!initBluetooth()) {
            return
        }

        configStorage = ConfigStorage(this)
        initViews()
    }
    // endregion

    // region 权限检查
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth(): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device doesn't support Bluetooth")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }
    // endregion

    // region UI初始化
    private fun initViews() {
        // 绑定视图组件
        radioGroup = findViewById(R.id.radio_device_type)
        inputTSBLU = findViewById(R.id.input_tsblu)
        radioRadar = findViewById(R.id.radio_radar)
        radioSleep = findViewById(R.id.radio_sleep)
        rvDevices = findViewById(R.id.rv_devices)
        btnBack = findViewById(R.id.btn_back)

        btnBack.setOnClickListener { finish() }

        // 初始化设备列表
        val deviceAdapter = DeviceAdapter(deviceList, configStorage.getDeviceHistories()) { device ->
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_DEVICE_TYPE, device.type.name)
                putExtra(EXTRA_DEVICE_ID, device.deviceId ?: device.name)
                putExtra(EXTRA_DEVICE_MAC, device.macAddress)
            })
            finish()
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // 设置扫描类型选择监听
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_radar -> {
                    val tsbluValue = inputTSBLU.text.toString().trim()
                    startRadarScan(filterTSBLU = if (tsbluValue.isEmpty()) null else tsbluValue)
                }
                R.id.radio_sleep -> {
                    startSleepScan()
                }
            }
        }
    }
    // endregion

    // region 扫描实现
    /**
     * A厂扫描实现
     * @param filterTSBLU TSBLU过滤值
     */
    private fun startRadarScan(filterTSBLU: String?) {
        stopCurrentScan()
        currentScanModule = "A厂"

        val radarManager = RadarBleManager.getInstance(this)
        radarManager.setScanCallback { result ->
            runOnUiThread {
                val device = DeviceInfo(
                    type = DeviceType.RADAR,
                    name = result.name ?: "",
                    macAddress = result.device.address,
                    rssi = result.rssi,
                    deviceId = result.name,
                    firmwareVersion = null
                )

                if (!deviceList.any { it.macAddress == device.macAddress }) {
                    deviceList.add(device)
                    rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                }
            }
        }

        radarManager.enableFilter(!filterTSBLU.isNullOrEmpty())
        radarManager.startScan()
    }

    /**
     * B厂扫描实现
     */
    private fun startSleepScan() {
        stopCurrentScan()
        currentScanModule = "B厂"

        // 启动B厂扫描Activity
        sleepaceScanLauncher.launch(Intent(this, Class.forName("com.bleconfig.sleepace.SearchBleDeviceActivity")))
    }

    private fun stopCurrentScan() {
        when (currentScanModule) {
            "A厂" -> RadarBleManager.getInstance(this).stopScan()
            "B厂" -> {
                // B厂扫描在Activity中自行处理
            }
        }
        deviceList.clear()
        rvDevices.adapter?.notifyDataSetChanged()
    }
    // endregion

    // region 结果处理
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                recreate()
            } else {
                finish()
            }
        }
    }
    // endregion

    // region 适配器实现
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

        override fun getItemCount(): Int = deviceList.size

        class DeviceViewHolder(
            itemView: View,
            private val onDeviceClick: (DeviceInfo) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val tvDeviceId: TextView = itemView.findViewById(R.id.tv_device_id)
            private val tvMacConfigTime: TextView = itemView.findViewById(R.id.tv_mac_config_time)
            private val tvRssi: TextView = itemView.findViewById(R.id.tv_rssi)

            fun bind(device: DeviceInfo, configuredDevices: List<DeviceHistory>) {
                tvDeviceId.text = device.deviceId ?: device.name

                val configTime = configuredDevices.find { it.macAddress == device.macAddress }?.configTime
                val macConfigTime = if (configTime != null) {
                    "${device.macAddress} / ${formatConfigTime(configTime)}"
                } else {
                    device.macAddress
                }
                tvMacConfigTime.text = macConfigTime
                tvRssi.text = "RSI: ${device.rssi}"

                itemView.setOnClickListener { onDeviceClick(device) }
            }

            private fun formatConfigTime(configTime: Long): String {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                return dateFormat.format(Date(configTime))
            }
        }
    }
    // endregion

    companion object {
        private const val TAG = "ScanActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_MAC = "device_mac"
    }
}