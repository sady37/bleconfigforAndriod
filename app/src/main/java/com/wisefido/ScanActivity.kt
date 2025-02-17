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
import com.espressif.espblufi.RadarBleManager
import com.bleconfig.sleepace.SleePaceBleManager
import com.sleepace.sdk.domain.BleDevice

class ScanActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScanActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        const val EXTRA_DEVICE = "extra_device"
        const val EXTRA_DEVICE_TYPE = "device_type"
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

    // 设备列表
    private val deviceList = mutableListOf<DeviceInfo>()

    // 当前扫描的厂家模块
    private var currentScanModule: String? = null

    // 启用蓝牙的新 API
    private val enableBluetoothRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled")
            recreate()
        } else {
            Log.w(TAG, "Bluetooth enable request denied")
            Toast.makeText(this, "需要开启蓝牙才能扫描设备", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter
    }

    private fun checkBluetooth(): Boolean {
        val bluetoothAdapter = getBluetoothAdapter()

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device doesn't support Bluetooth")
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission")
                return false
            }
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            enableBluetoothRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }

        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_scan)

        // 1. 先检查所有必需的权限
        if (!checkAndRequestPermissions()) {
            Log.w(TAG, "Missing required permissions")
            return
        }

        // 2. 检查蓝牙
        if (!checkBluetooth()) {
            return
        }

        // 3. 初始化配置存储和视图
        configStorage = ConfigStorage(this)
        initViews()
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()

        // 检查定位权限
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

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissions(permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }

        return true
    }

    private fun initViews() {
        Log.d(TAG, "Initializing views")
        // 绑定视图组件
        radioGroup = findViewById(R.id.radio_device_type)
        radioRadarQL = findViewById(R.id.radio_radarQL)
        radioSleepace = findViewById(R.id.radio_sleepace)
        radioEsp = findViewById(R.id.radio_ESP)
        inputFilter = findViewById(R.id.input_filter)
        rvDevices = findViewById(R.id.rv_devices)
        btnBack = findViewById(R.id.btn_back)

        // 设置返回按钮的点击事件
        btnBack.setOnClickListener {
            Log.d(TAG, "Back button clicked")
            stopCurrentScan()
            finish()
        }

        // 初始化 RecyclerView
        /*val deviceAdapter = DeviceAdapter(deviceList, configStorage.getDeviceHistories()) { device ->

            Log.i(TAG, "Device selected: ${device.deviceId}, MAC: ${device.macAddress}")
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_DEVICE, device.originalDevice)
                putExtra(EXTRA_DEVICE_TYPE, device.type.name)
            })
            stopCurrentScan()
            finish()
        }
        */
        // 修改 DeviceAdapter 中的设备点击处理
        val deviceAdapter = DeviceAdapter(deviceList, configStorage.getDeviceHistories()) { device ->
            Log.i(TAG, "Device selected: ${device.deviceId}, MAC: ${device.macAddress}")

            val intent = Intent()
            when (device.type) {
                DeviceType.radarQL -> {
                    // A 厂设备是 ScanResult，实现了 Parcelable
                    intent.putExtra(EXTRA_DEVICE, device.originalDevice as ScanResult)
                }
                DeviceType.sleepace -> {
                    // B 厂设备是 BleDevice，实现了 Serializable
                    intent.putExtra(EXTRA_DEVICE, device.originalDevice as BleDevice)
                }
            }
            intent.putExtra(EXTRA_DEVICE_TYPE, device.type.name)

            setResult(RESULT_OK, intent)
            stopCurrentScan()
            finish()
        }

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // 设置 RadioGroup 的监听器
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            // 清除当前扫描
            if (currentScanModule == "A厂") {
                RadarBleManager.getInstance(this).stopScan()
            }
            deviceList.clear()
            rvDevices.adapter?.notifyDataSetChanged()

            // 控制过滤器输入框的显示/隐藏
            inputFilter.visibility = if (checkedId == R.id.radio_ESP) View.VISIBLE else View.GONE
            if (checkedId != R.id.radio_ESP) {
                inputFilter.setText("") // 清除输入内容
            }

            when (checkedId) {
                R.id.radio_radarQL -> {
                    Log.d(TAG, "Starting Radar QL scan")
                    startRadarScan("TSBLU")
                }
                R.id.radio_sleepace -> {
                    Log.d(TAG, "Starting Sleepace scan")
                    val sleepaceManager = SleePaceBleManager.getInstance(this)
                    sleepaceManager.startScan(null)  // 启动 B 厂的扫描 Activity
                }
                R.id.radio_ESP -> {
                    val filterValue = inputFilter.text.toString().trim()
                    Log.d(TAG, "Starting ESP scan with filter: ${if (filterValue.isEmpty()) "none" else filterValue}")
                    startRadarScan(filterValue)
                }
            }
        }

        // 默认选中Radar并触发扫描
        radioRadarQL.isChecked = true
    }

    private fun startRadarScan(filterPrefix: String) {
        stopCurrentScan()
        currentScanModule = "A厂"
        Log.d(TAG, "Starting Radar scan with filter: ${if (filterPrefix.isEmpty()) "none" else filterPrefix}")

        // 添加权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing Bluetooth permissions")
                return
            }
        }

        val radarManager = RadarBleManager.getInstance(this)
        radarManager.setScanCallback { result ->
            try {
                val deviceName = result.device.name ?: return@setScanCallback

                // 空值时不过滤，否则按前缀过滤
                if (filterPrefix.isNotEmpty() && !deviceName.startsWith(filterPrefix)) {
                    return@setScanCallback
                }

                Log.d(TAG, "Found device: $deviceName, MAC: ${result.device.address}, RSSI: ${result.rssi}")

                runOnUiThread {
                    val device = DeviceInfo(
                        type = DeviceType.radarQL,
                        deviceId = deviceName,
                        macAddress = result.device.address,
                        rssi = result.rssi,
                        originalDevice = result
                    )

                    if (!deviceList.any { it.macAddress == device.macAddress }) {
                        val insertPosition = deviceList.size
                        deviceList.add(device)
                        rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception while scanning", e)
            }
        }

        radarManager.enableFilter(filterPrefix.isNotEmpty())
        radarManager.startScan()
    }

    private fun stopCurrentScan() {
        Log.d(TAG, "Stopping current scan, module: $currentScanModule")
        if (currentScanModule == "A厂") {
            RadarBleManager.getInstance(this).stopScan()
        }
        val oldSize = deviceList.size
        deviceList.clear()
        rvDevices.adapter?.notifyItemRangeRemoved(0, oldSize)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: $requestCode")

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i(TAG, "All permissions granted")
                recreate()
            } else {
                Log.w(TAG, "Some permissions denied")
                Toast.makeText(this, "需要蓝牙和定位权限才能扫描设备", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopCurrentScan()
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