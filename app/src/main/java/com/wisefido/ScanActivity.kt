package com.wisefido

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.annotation.SuppressLint

import com.espressif.espblufi.RadarBleManager
import com.bleconfig.sleepace.SleePaceBleManager
import com.sleepace.sdk.domain.BleDevice

import android.app.Dialog
import android.widget.Filter

class ConfigDialog(context: Context) : Dialog(context) {
    private lateinit var etRadarName: EditText
    private lateinit var radioFilterType: RadioGroup

    // 配置更改监听器
    private var onConfigChangedListener: ((String, String) -> Unit)? = null

    init {
        // 设置对话框布局
        setContentView(R.layout.popup_configure)

        // 绑定视图组件
        etRadarName = findViewById(R.id.et_radar_name)
        radioFilterType = findViewById(R.id.radio_filter_type)

        // 确认按钮点击事件
        findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            // 获取用户输入的雷达设备名称
            val radarName = etRadarName.text.toString()

            // 获取用户选择的过滤器类型
            val filterType = when (radioFilterType.checkedRadioButtonId) {
                R.id.radio_device_name -> FilterType.DEVICE_NAME
                R.id.radio_mac -> FilterType.MAC
                R.id.radio_uuid -> FilterType.UUID
                else -> FilterType.DEVICE_NAME
            }

            // 触发配置更改监听器
            onConfigChangedListener?.invoke(radarName, filterType)

            // 关闭对话框
            dismiss()
        }

        // 取消按钮点击事件
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            // 关闭对话框
            dismiss()
        }
    }

    /**
     * 设置雷达设备名称
     */
    fun setRadarDeviceName(name: String) {
        etRadarName.setText(name)
    }

    /**
     * 设置过滤器类型
     */
    fun setFilterType(filterType: String) {
        when (filterType) {
            FilterType.DEVICE_NAME -> radioFilterType.check(R.id.radio_device_name)
            FilterType.MAC -> radioFilterType.check(R.id.radio_mac)
            FilterType.UUID -> radioFilterType.check(R.id.radio_uuid)
        }
    }

    /**
     * 设置配置更改监听器
     */
    fun setOnConfigChangedListener(listener: (String, String) -> Unit) {
        this.onConfigChangedListener = listener
    }
}

class ScanActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScanActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        const val EXTRA_DEVICE = "extra_device"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_INFO = "extra_device_info"  // 添加这一行
    }

    // 视图组件
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioRadar: RadioButton
    private lateinit var radioSleepace: RadioButton
    private lateinit var radioFilter:  RadioButton
    private lateinit var inputFilter: EditText
    private lateinit var rvDevices: RecyclerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnScan: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var filterLabel: TextView

    // 数据存储
    private lateinit var configScan: ConfigStorage

    // 设备列表
    private val deviceList = mutableListOf<DeviceInfo>()

    // 当前扫描的厂家模块
    private var currentScanModule: String? = null

    // 扫描状态
    private var isScanning = false

    // 配置参数
    //private var radarDeviceName = DefaultConfig.RADAR_DEVICE_NAME
    private var currentFilterType = DefaultConfig.DEFAULT_FILTER_TYPE // 已经是 String 类型
    private var currentFilterPrefix = DefaultConfig.DEFAULT_FILTER_PREFIX


    // 启用蓝牙的新 API
    private val enableBluetoothRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled")
            recreate()
        } else {
            Log.w(TAG, "Bluetooth enable request denied")
            Toast.makeText(this, getString(R.string.bluetooth_permission_required), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        // 初始化配置存储和视图
        configScan = ConfigStorage(this)
        initViews()
    }

    /**
     * 初始化视图组件
     */
    private fun initViews() {
        Log.d(TAG, "Initializing views")

        // 绑定视图组件
        radioGroup = findViewById(R.id.radio_device_type)
        radioRadar = findViewById(R.id.radio_radar)
        radioSleepace = findViewById(R.id.radio_sleepace)
        radioFilter = findViewById(R.id.radio_filter)
        inputFilter = findViewById(R.id.input_filter)
        rvDevices = findViewById(R.id.rv_devices)
        btnBack = findViewById(R.id.btn_back)
        btnScan = findViewById(R.id.btn_scan)
        btnConfig = findViewById(R.id.btn_config)
        filterLabel = findViewById(R.id.filter_label)


        // 设置返回按钮的点击事件
        btnBack.setOnClickListener {
            stopScan()
            finish()
        }

        // 设置扫描按钮的点击事件
        btnScan.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                startScan()
            }
        }

        // 设置配置按钮的点击事件
        btnConfig.setOnClickListener {
            showConfigDialog()
        }

        // 初始化 RecyclerView
        val deviceAdapter = DeviceAdapter(deviceList, configScan.getDeviceHistories()) { device ->
            Log.i(TAG, "Device selected: ${device.deviceId}, MAC: ${device.macAddress}")

            val intent = Intent().apply {
                // 只传递必要信息
                putExtra("productor_name", device.productorName)  // 厂商标识
                putExtra("rssi", device.rssi)                    // 信号强度

                // 原始设备对象仍然需要传递，因为配网时需要
                when (device.productorName) {
                    Productor.radarQL, Productor.espBle -> {
                        putExtra(EXTRA_DEVICE, device.originalDevice as ScanResult)
                    }
                    Productor.sleepBoardHS -> {
                        putExtra(EXTRA_DEVICE, device.originalDevice as BleDevice)
                    }
                }
            }

            setResult(RESULT_OK, intent)
            stopScan()
            finish()
        }

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // 设置 RadioGroup 的监听器
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            // 仅更新当前选择的设备类型，不自动启动扫描
            when (checkedId) {
                R.id.radio_radar -> {
                    currentScanModule = Productor.radarQL
                    currentFilterPrefix = configScan.getRadarDeviceName()
                    currentFilterType = FilterType.DEVICE_NAME
                }
                R.id.radio_sleepace -> {
                    currentScanModule = Productor.sleepBoardHS
                    // 清除过滤参数
                    currentFilterPrefix  = ""
                    currentFilterType = FilterType.DEVICE_NAME
                }
                R.id.radio_filter -> {  // 假设你有这个 ID
                    currentScanModule = Productor.espBle
                    // 设置 ESP 的过滤参数
                    currentFilterPrefix = inputFilter.text.toString()
                    currentFilterType = configScan.getFilterType()
                }
            }
        }

        // 默认选中 Radar
        radioRadar.isChecked = true
    }


    /**
     * 启动扫描
     */
    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) {
            stopScan()
            return
        }

        // 检查蓝牙扫描权限
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            // 权限已授予，启动扫描
            isScanning = true
            btnScan.setText(R.string.stop)
            deviceList.clear()
            rvDevices.adapter?.notifyDataSetChanged()

            when (currentScanModule) {
                Productor.radarQL -> startRadarScan()
                Productor.sleepBoardHS -> startSleepaceScan()
                Productor.espBle -> startRadarScan()
            }
        } else {
            // 权限未授予，动态申请权限
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN), PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * 启动 Radar 扫描
     */
    @SuppressLint("MissingPermission")
    private fun startRadarScan() {
        val radarManager = RadarBleManager.getInstance(this)
        Log.d(TAG, "Starting radar scan with filter: $currentFilterPrefix, type: $currentFilterType")
        radarManager.setScanCallback { result ->
            Log.d(TAG, "Received scan result: ${result.device.name}, ${result.device.address}")
            val device = DeviceInfo(
                productorName = Productor.radarQL,           // 厂家标识
                deviceName = result.device.name ?: "",       // 实际扫描到的设备名称
                deviceId = result.device.name ?: "",         // 显示用的设备标识
                macAddress = result.device.address,
                rssi = result.rssi,
                originalDevice = result
            )

            runOnUiThread {
                if (!deviceList.any { it.macAddress == device.macAddress }) {
                    deviceList.add(device)
                    rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                }
            }
        }
        radarManager.startScan(currentFilterPrefix, currentFilterType)
    }

    /**
     * 启动 Sleepace 扫描
     */
    @SuppressLint("MissingPermission")
    private fun startSleepaceScan() {
        val sleepaceManager = SleePaceBleManager.getInstance(this)
        sleepaceManager.startScan { result ->
            runOnUiThread {
                if (result.status == StatusCode.SUCCESS) {
                    val bleDevice = result.data as BleDevice
                    val device = DeviceInfo(
                        productorName = Productor.sleepBoardHS,     // 厂家标识
                        deviceName = bleDevice.deviceName ?: "",    // 实际扫描到的设备名称
                        deviceId = bleDevice.deviceName ?: "",      // 显示用的设备标识
                        macAddress = bleDevice.address,
                        rssi = 0,
                        originalDevice = bleDevice
                    )

                    if (!deviceList.any { it.macAddress == device.macAddress }) {
                        deviceList.add(device)
                        rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                    }
                }
            }
        }
    }

    /**
     * 停止扫描
     */
    private fun stopScan() {
        Log.d(TAG, "Stopping current scan, module: $currentScanModule")
        isScanning = false
        btnScan.setText(R.string.scan)

        // 清空设备列表
        val oldSize = deviceList.size
        deviceList.clear()
        rvDevices.adapter?.notifyItemRangeRemoved(0, oldSize)
    }

    /**
     * 显示配置对话框
     */
    private fun showConfigDialog() {
        ConfigDialog(this).apply {
            // 设置当前配置
            setRadarDeviceName(currentFilterPrefix )
            setFilterType(currentFilterType)

            // 设置配置更改监听器
            setOnConfigChangedListener { newName, newType ->
                currentFilterPrefix  = newName
                currentFilterType = newType

                // === 新增：更新filterLabel和输入框提示 ===
                when (newType) {
                    FilterType.DEVICE_NAME -> {
                        filterLabel.text = "Filter by: Device Name"
                        inputFilter.hint = "TSBLU, BM..."  // 简化的设备名格式示例
                    }
                    FilterType.MAC -> {
                        filterLabel.text = "Filter by: MAC"
                        inputFilter.hint = "XX:XX:XX:XX:XX:XX"  // MAC地址格式示例
                    }
                    FilterType.UUID -> {
                        filterLabel.text = "Filter by: UUID"
                        inputFilter.hint = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"  // UUID格式示例
                    }
                }

                // 保存配置到 SharedPreferences
                configScan.saveRadarDeviceName(newName)
                configScan.saveFilterType(newType)

                // 如果正在扫描，则停止并重新启动扫描
                if (isScanning) {
                    stopScan()
                    startScan()
                }
            }

            // 显示对话框
            show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，重新启动扫描
                startScan()
            } else {
                // 权限被拒绝，提示用户
                Toast.makeText(this, "Bluetooth scan permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }

    /**
     * 设备列表适配器
     */
    private class DeviceAdapter(
        private val deviceList: List<DeviceInfo>,
        private val configuredDevices: List<DeviceHistory>,
        private val onDeviceClick: (DeviceInfo) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = deviceList[position]
            holder.bind(device, configuredDevices)
            holder.itemView.setOnClickListener { onDeviceClick(device) }
        }

        override fun getItemCount(): Int = deviceList.size

        class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDeviceId: TextView = itemView.findViewById(R.id.tv_device_id)
            private val tvMacConfigTime: TextView = itemView.findViewById(R.id.tv_mac_config_time)
            private val tvRssi: TextView = itemView.findViewById(R.id.tv_rssi)

            fun bind(device: DeviceInfo, configuredDevices: List<DeviceHistory>) {
                tvDeviceId.text = device.deviceId

                val configTime = configuredDevices.find { it.macAddress == device.macAddress }?.configTime
                val macConfigTime = if (configTime != null) {
                    "${device.macAddress} / ${formatConfigTime(configTime)}"
                } else {
                    device.macAddress
                }
                tvMacConfigTime.text = macConfigTime
                tvRssi.text = itemView.context.getString(R.string.format_rssi, device.rssi)
            }

            private fun formatConfigTime(configTime: Long): String {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                return dateFormat.format(Date(configTime))
            }
        }
    }
}