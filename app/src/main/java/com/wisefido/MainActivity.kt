/**
 * File: MainActivity.kt
 * Package: com.wisefido
 *
 * 目录：
 * 1. 属性定义
 * 2. Activity生命周期
 * 3. UI初始化和配置
 * 4. 历史记录管理
 * 5. 扫描和配网操作
 * 6. 配置验证和保存
 * 7. 结果处理
 */

package com.wisefido

// Android 标准库
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import android.util.Log
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.widget.TextView
import android.widget.ImageButton  // [新增] 解决ImageButton未定义的问题
import android.app.Dialog  // 添加Dialog导入
import android.widget.ProgressBar  // 添加ProgressBar导入
import android.text.InputFilter
import android.text.InputType

//common data type
import com.common.DeviceInfo
import com.common.Productor
import com.common.FilterType
import com.common.DeviceHistory
import com.common.ServerConfig
import com.common.WifiConfig
import com.common.DefaultConfig

// A厂 SDK
import com.espressif.espblufi.params.BlufiConfigureParams
import com.espressif.espblufi.params.BlufiParameter
import com.espressif.espblufi.RadarBleManager
import android.bluetooth.le.ScanResult  // A 厂扫描结果

// B厂 SDK
import com.sleepace.sdk.constant.StatusCode
import com.sleepace.sdk.domain.BleDevice
import com.bleconfig.sleepace.SleepaceBleManager


//自已的引用


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        // 定义所需权限
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }
    }

    // region 属性定义
    //private lateinit var etDeviceId: TextInputEditText
    //private lateinit var btnScan: MaterialButton
    private lateinit var etServerAddress: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var etWifiSsid: TextInputEditText
    private lateinit var etWifiPassword: TextInputEditText
    private lateinit var btnPair: MaterialButton
    private lateinit var tvRecentServer: MaterialTextView
    private lateinit var tvRecentWifi: MaterialTextView
    private lateinit var layoutServerHistory: View
    private lateinit var layoutWifiHistory: View
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvDeviceRssi: TextView
    private lateinit var btnSearch: ImageButton
    private lateinit var layoutDeviceInfo: View
    private lateinit var configScan: ConfigStorage
    private var lastScannedBleDevice: BleDevice? = null
    private var selectedDevice: DeviceInfo? = null





    // Activity Result API
// 扫描结果处理需要修改为：
    @SuppressLint("MissingPermission")
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Scan result received with RESULT_OK")
            result.data?.let { intent ->
                val deviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(ScanActivity.EXTRA_DEVICE_INFO, DeviceInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(ScanActivity.EXTRA_DEVICE_INFO) as? DeviceInfo
                }

                Log.d(TAG, "Received DeviceInfo: ${deviceInfo?.deviceName}")
                selectedDevice = deviceInfo
                updateDeviceDisplay(deviceInfo)
            }
        } else {
            Log.d(TAG, "Scan result received with result code: ${result.resultCode}")
        }
    }

    private fun updateDeviceDisplay(device: DeviceInfo?) {
        if (device == null) {
            tvDeviceName.text = "No Device"
            tvDeviceId.text = "No ID"
            tvDeviceRssi.text = "--"
            return
        }

        // 更新设备信息显示
        layoutDeviceInfo.visibility = View.VISIBLE
        tvDeviceName.text = device.deviceName ?: ""
        tvDeviceId.text = device.deviceId ?: ""
        tvDeviceRssi.text = "${device.rssi}dBm"
    }

    private val configLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val success = result.data?.getBooleanExtra("config_success", false) ?: false
            handleConfigResult(success)
        }
    }
    // endregion

    // region Activity生命周期
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configScan = ConfigStorage(this)
        initViews()
        setupHistoryViews()
        loadRecentConfigs()
    }
    // endregion

    // region UI初始化
    private fun initViews() {
        etServerAddress = findViewById(R.id.et_server_address)
        etServerPort = findViewById(R.id.et_server_port)
        etWifiSsid = findViewById(R.id.et_wifi_ssid)
        etWifiPassword = findViewById(R.id.et_wifi_password)
        btnPair = findViewById(R.id.btn_pair)
        tvRecentServer = findViewById(R.id.tv_recent_server)
        tvRecentWifi = findViewById(R.id.tv_recent_wifi)
        layoutServerHistory = findViewById(R.id.layout_server_history)
        layoutWifiHistory = findViewById(R.id.layout_wifi_history)
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceId = findViewById(R.id.tv_device_id)
        tvDeviceRssi = findViewById(R.id.tv_device_rssi)  // 添加 RSSI TextView
        layoutDeviceInfo = findViewById(R.id.layout_device_info)
        btnSearch = findViewById(R.id.btn_search)

        // 添加输入过滤器，去除空格
        val noSpaceFilter = InputFilter { source, start, end, dest, dstart, dend ->
            source.toString().trim { it <= ' ' }
        }

        // 应用到所有输入框
        etServerAddress.filters = arrayOf(noSpaceFilter)
        etServerPort.filters = arrayOf(noSpaceFilter)
        etWifiSsid.filters = arrayOf(noSpaceFilter)
        etWifiPassword.filters = arrayOf(noSpaceFilter)

        // 初始隐藏设备信息区域
        //layoutDeviceInfo.visibility = View.GONE
        updateDeviceDisplay(null)  // 显示空状态而不是隐藏
        btnSearch.setOnClickListener {
            startScanActivity()
        }

        // 配对按钮点击事件
        btnPair.setOnClickListener {
            handlePairClick()
        }

        // 历史记录点击事件
        layoutServerHistory.setOnClickListener {
            showServerHistoryMenu(it)
        }

        layoutWifiHistory.setOnClickListener {
            showWifiHistoryMenu(it)
        }

        // 设置历史记录标题的时钟图标
        val clockDrawable = ContextCompat.getDrawable(this, R.drawable.ic_history_24)
        clockDrawable?.setTint(ContextCompat.getColor(this, R.color.text_secondary))
        tvRecentServer.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
        tvRecentWifi.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)

        // 加载最近配置
        loadRecentConfigs()
    }

    private fun setupHistoryViews() {
        val clockDrawable = ContextCompat.getDrawable(this, R.drawable.ic_history_24)
        clockDrawable?.setTint(ContextCompat.getColor(this, R.color.text_secondary))
        tvRecentServer.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
        tvRecentWifi.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
    }
    // endregion

    // region 历史记录管理
    private fun showServerHistoryMenu(anchor: View) {
        val recentServers = configScan.getServerConfigs().take(5)
        if (recentServers.isEmpty()) return

        PopupMenu(this, anchor).apply {
            recentServers.forEach { server ->
                menu.add("${server.serverAddress}:${server.protocol}${server.port}")
                    .setOnMenuItemClickListener {
                        etServerAddress.setText(server.serverAddress)
                        etServerPort.setText("${server.protocol}${server.port}")
                        true
                    }
            }
            show()
        }
    }

    private fun showWifiHistoryMenu(anchor: View) {
        val recentWifis = configScan.getWifiConfigs().take(5)
        if (recentWifis.isEmpty()) return

        PopupMenu(this, anchor).apply {
            recentWifis.forEach { wifi ->
                menu.add(wifi.ssid)
                    .setOnMenuItemClickListener {
                        etWifiSsid.setText(wifi.ssid)
                        etWifiPassword.setText(wifi.password)
                        true
                    }
            }
            show()
        }
    }

    private fun loadRecentConfigs() {
        val recentServers = configScan.getServerConfigs()
        if (recentServers.isNotEmpty()) {
            tvRecentServer.text = getString(R.string.recent_servers_count, recentServers.size.coerceAtMost(5))
            layoutServerHistory.visibility = View.VISIBLE
        } else {
            layoutServerHistory.visibility = View.GONE
        }

        val recentWifis = configScan.getWifiConfigs()
        if (recentWifis.isNotEmpty()) {
            tvRecentWifi.text = getString(R.string.recent_networks_count, recentWifis.size.coerceAtMost(5))
            layoutWifiHistory.visibility = View.VISIBLE
        } else {
            layoutWifiHistory.visibility = View.GONE
        }
    }
    // endregion

    // region 扫描和配网操作
    private fun startScanActivity() {
        if (REQUIRED_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
            return
        }

        saveCurrentConfig()
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // 所有权限都获取成功，启动扫描
                    startScanActivity()
                } else {
                    showMessage(getString(R.string.permissions_required))
                }
            }
        }
    }


    private fun handlePairClick() {
        saveCurrentConfig()

        if (!validateInput()) {
            return
        }

        if (selectedDevice == null) {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }

        when (selectedDevice?.productorName) {
            Productor.radarQL -> startRadarConfig()
            Productor.sleepBoardHS -> startSleepConfig()
            Productor.espBle -> startRadarConfig()
            else -> showMessage(getString(R.string.toast_unknown_device_type))
        }
    }

    /**
     * A厂(Radar)配网实现
     */
    @SuppressLint("MissingPermission")
    private fun startRadarConfig() {
        // 检查设备信息
        val deviceInfo = selectedDevice ?: run {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }

        // 获取原始设备对象
        val scanResult = deviceInfo.originalDevice as? ScanResult ?: run {
            showMessage(getString(R.string.toast_invalid_device))
            return
        }

        val radarManager = RadarBleManager.getInstance(this)
        val serverConfig = getCurrentServerConfig() ?: return
        val wifiConfig = getCurrentWifiConfig() ?: return

        // 创建配置参数
        val params = BlufiConfigureParams().apply {
            opMode = BlufiParameter.OP_MODE_STA  // Station模式
            setStaSSIDBytes(wifiConfig.ssid.toByteArray())  // 使用setStaSSIDBytes并转换为字节数组
            setStaPassword(wifiConfig.password)
        }

        // 开始配网
        radarManager.connect(scanResult.device)
        radarManager.configure(params) { success ->
            runOnUiThread {
                handleConfigResult(success)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSleepConfig() {
        // 检查设备信息
        val deviceInfo = selectedDevice ?: run {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }

        // 从原始设备中获取 BleDevice
        val bleDevice = deviceInfo.originalDevice as? BleDevice ?: run {
            showMessage(getString(R.string.toast_invalid_device))
            return
        }

        // 检查是否是UDP端口
        val portStr = etServerPort.text.toString().lowercase()
        if (portStr.startsWith("udp")) {
            showMessage("Invalid port, need tcp port")
            return
        }

        val sleepaceManager = SleepaceBleManager.getInstance(this)

        // 显示配置进度
        showMessage("Configuring...", showProgress = true)

        try {
            sleepaceManager.startConfig(
                bleDevice,
                etServerAddress.text.toString(),
                etServerPort.text.toString().replace("tcp", "").replace("TCP", "").toInt(),
                getWifiSsidRawData(),
                etWifiPassword.text.toString()
            ) { callbackData ->
                runOnUiThread {
                    when (callbackData.status) {
                        StatusCode.SUCCESS -> {
                            hideMessage()
                            handleConfigResult(true)
                            if (callbackData.result is DeviceInfo) {
                                val deviceInfo = callbackData.result as DeviceInfo
                                Log.d(TAG, "WiFi configuration successful - Device ID: ${deviceInfo.deviceId}")
                            }
                            showMessage("""
                                WiFi configuration successful.
                                Radar Light:Green
                                SleepBoard WiFi Light:
                                Solid Red->wifi connect fail
                                Flashing red-> Wifi connect success,Server Connect Fail
                                Solid Green-> wifi connect Success,Server connect Success
                            """.trimIndent())
                        }
                        StatusCode.TIMEOUT -> {
                            hideMessage()
                            handleConfigResult(false)
                            showMessage("WiFi configuration timeout")
                        }
                        StatusCode.DISCONNECT -> {
                            showMessage("Device disconnected, retrying...", showProgress = true)
                            handleConfigResult(false)
                        }
                        StatusCode.PARAMETER_ERROR -> {
                            hideMessage()
                            handleConfigResult(false)
                            showMessage("Invalid WiFi configuration parameters")
                        }
                        else -> {
                            hideMessage()
                            handleConfigResult(false)
                            showMessage("""
                                WiFi configuration fail.
                                WiFi light:Red, Blue:connect other mobile 
                                Solid Red->wifi connect fail
                                Flashing red-> Wifi connect success,Server Connect Fail
                                Solid Green-> wifi connect Success,Server connect Success
                            """.trimIndent())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config error", e)
            hideMessage()
            showMessage(getString(R.string.toast_config_exception))
        }
    }
    // endregion

    // region 配置验证和保存
    private fun parseProtocolAndPort(input: String): Pair<String, Int>? {
        val regex = Regex("^(tcp|udp)?(\\d+)$", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(input)

        return if (matchResult != null) {
            val protocol = matchResult.groupValues[1].lowercase().ifEmpty { "tcp" }
            val port = matchResult.groupValues[2].toIntOrNull()

            if (port != null && port in 1..65535) {
                Pair(protocol, port)
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun saveCurrentConfig() {
        val serverConfig = getCurrentServerConfig()
        val wifiConfig = getCurrentWifiConfig()

        serverConfig?.let { configScan.saveServerConfig(it) }
        wifiConfig?.let { configScan.saveWifiConfig(it) }
    }

    private fun getCurrentServerConfig(): ServerConfig? {
        val serverAddress = etServerAddress.text.toString()
        val portInput = etServerPort.text.toString()
        val protocolAndPort = parseProtocolAndPort(portInput)

        return protocolAndPort?.let { (protocol, port) ->
            if (serverAddress.isNotEmpty() && port > 0) {
                ServerConfig(serverAddress, port, protocol)
            } else null
        }
    }

    private fun getCurrentWifiConfig(): WifiConfig? {
        val wifiSsid = etWifiSsid.text.toString()
        val wifiPassword = etWifiPassword.text.toString()

        return if (wifiSsid.isNotEmpty() && wifiPassword.isNotEmpty()) {
            WifiConfig(wifiSsid, wifiPassword)
        } else null
    }

    private fun getWifiSsidRawData(): ByteArray {
        return etWifiSsid.text.toString().toByteArray()
    }

    private fun validateInput(): Boolean {
        if (etServerAddress.text.isNullOrEmpty()) {
            showMessage(getString(R.string.toast_enter_server_address))
            return false
        }

        val portInput = etServerPort.text.toString()
        if (portInput.isEmpty()) {
            showMessage(getString(R.string.toast_enter_server_port))
            return false
        }

        val protocolAndPort = parseProtocolAndPort(portInput)
        if (protocolAndPort == null) {
            showMessage(getString(R.string.toast_invalid_port_format))
            return false
        }

        if (etWifiSsid.text.isNullOrEmpty()) {
            showMessage(getString(R.string.toast_enter_wifi_name))
            return false
        }

        if (etWifiPassword.text.isNullOrEmpty()) {
            showMessage(getString(R.string.toast_enter_wifi_password))
            return false
        }

        return true
    }
    // endregion

    // region 结果处理
    @SuppressLint("MissingPermission")
    private fun handleConfigResult(success: Boolean) {
        if (success) {
            // 获取设备的 MAC 地址和名称
            val deviceMac = when (val device = selectedDevice?.originalDevice) {
                is ScanResult -> device.device.address
                is BleDevice -> device.address
                else -> return
            }

            val deviceName = when (val device = selectedDevice?.originalDevice) {
                is ScanResult -> device.device.name ?: ""
                is BleDevice -> device.deviceName ?: ""
                else -> return
            }

            getCurrentServerConfig()?.let { serverConfig ->
                getCurrentWifiConfig()?.let { wifiConfig ->
                    configScan.saveDeviceHistory(
                        DeviceHistory(
                            deviceName = deviceName,
                            macAddress = deviceMac,
                            rssi = selectedDevice?.rssi ?: -255,
                            serverConfig = serverConfig,
                            wifiConfig = wifiConfig
                        )
                    )
                }
            }
            loadRecentConfigs()
            showMessage(getString(R.string.toast_config_success))
        } else {
//            showMessage(getString(R.string.toast_config_failed))
        }
    }

    private var mDialog: Dialog? = null

    private fun showMessage(message: String, showProgress: Boolean = false) {
        if(mDialog == null) {
            mDialog = Dialog(this).apply {
                setContentView(R.layout.dialog_message)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        }

        mDialog?.findViewById<TextView>(R.id.message)?.text = message
        mDialog?.findViewById<ProgressBar>(R.id.progress)?.visibility =
            if(showProgress) View.VISIBLE else View.GONE

        if(!mDialog?.isShowing!!) {
            mDialog?.show()
        }
    }

    private fun hideMessage() {
        mDialog?.dismiss()
    }
    // endregion

}