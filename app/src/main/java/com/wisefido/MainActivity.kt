/**
 * File: MainActivity.kt
 * Package: com.wisefido
 *
 * 目录：
 * 1. 属性定义
 * 2. Activity生命周期
 * 3. UI初始化和配置
 * 4. 历史记录管理
 * 5. 扫描
 * 6. 配网操作
 * 7. 设备状态查询
 * 8. 配置验证和保存
 * 9. 结果处理
 */

package com.wisefido

// Android 标准库
import android.annotation.SuppressLint
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


import android.text.InputFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper


//common data type
import com.common.DeviceInfo
import com.common.Productor
import com.common.DeviceHistory
import com.common.ServerConfig
import com.common.WifiConfig
import com.common.BleDeviceManager

// A厂 SDK
import com.espressif.espblufi.RadarBleManager


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
        private val REQUIRED_PERMISSIONS =
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
    }

    // region 属性定义
    private lateinit var etServerAddress: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var etWifiSsid: TextInputEditText
    private lateinit var etWifiPassword: TextInputEditText
    private lateinit var btnPair: MaterialButton
    private lateinit var btnStatus: MaterialButton
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
    private lateinit var tvStatusOutput: TextView


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

    @SuppressLint("SetTextI18n")
    private fun updateDeviceDisplay(device: DeviceInfo?) {
        if (device == null) {
            tvDeviceName.text = "No Device"
            tvDeviceId.text = "No ID"
            tvDeviceRssi.text = "--"
            return
        }

        // 更新设备信息显示
        layoutDeviceInfo.visibility = View.VISIBLE
        tvDeviceName.text = device.deviceName
        tvDeviceId.text = device.deviceId
        tvDeviceRssi.text = device.rssi.toString() + "dBm"
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
        btnStatus = findViewById(R.id.btn_status)
        tvRecentServer = findViewById(R.id.tv_recent_server)
        tvRecentWifi = findViewById(R.id.tv_recent_wifi)
        layoutServerHistory = findViewById(R.id.layout_server_history)
        layoutWifiHistory = findViewById(R.id.layout_wifi_history)
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceId = findViewById(R.id.tv_device_id)
        tvDeviceRssi = findViewById(R.id.tv_device_rssi)  // 添加 RSSI TextView
        layoutDeviceInfo = findViewById(R.id.layout_device_info)
        btnSearch = findViewById(R.id.btn_search)
        tvStatusOutput = findViewById(R.id.tv_status_output)

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

        btnStatus.setOnClickListener {
            handleStatusClick()
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
    @SuppressLint("SetTextI18n")
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
                menu.add(wifi.ssid.toString())
                    .setOnMenuItemClickListener {
                        etWifiSsid.setText(wifi.ssid.toString())
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

    // region 扫描
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

    // endregion

    // region配网操作

    private fun handlePairClick() {
        // 首先确保没有扫描在进行
        RadarBleManager.getInstance(this).stopScan()
        SleepaceBleManager.getInstance(this).stopScan()
        // 验证设备选择
        if (selectedDevice == null) {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }


        // 根据设备类型进行不同的验证和配网
        when (selectedDevice?.productorName) {
            Productor.radarQL, Productor.espBle -> {
                // A厂设备允许分离配置
                val hasValidWifi = getCurrentWifiConfig() != null
                val hasValidServer = getCurrentServerConfig() != null

                // 如果两者都无效，给出提示
                if (!hasValidWifi && !hasValidServer) {
                    showMessage("Please enter at least a valid WiFi or server configuration")
                    return
                }
                // 如果两者都需要配置，先配置WiFi，然后通过回调处理服务器配置
                if (hasValidWifi && hasValidServer) {
                    // 只启动WiFi配置，服务器配置将在WiFi成功后的回调中启动
                    configureRadarWiFi()
                }
                // 只配置WiFi
                else if (hasValidWifi) {
                    configureRadarWiFi()
                }
                // 只配置服务器
                else {
                    configureRadarServer()
                }
            }

            Productor.sleepBoardHS -> {
                // B厂设备需要同时配置WiFi和服务器
                val serverConfig = getCurrentServerConfig()
                val wifiConfig = getCurrentWifiConfig()

                if (serverConfig == null || wifiConfig == null) {
                    showMessage("Both valid WiFi and server configuration are required")
                    return
                }

                // 两者都有效，开始配网
                startSleepConfig()
            }

            else -> showMessage(getString(R.string.toast_unknown_device_type))
        }
    }

    /**
     * A厂(Radar)配网实现
     */
    @SuppressLint("MissingPermission", "SetTextI18x")
    private fun configureRadarWiFi() {
        val deviceAdd = selectedDevice?.macAddress ?: return
        val wifiConfig = getCurrentWifiConfig() ?: return
        val ssidString = wifiConfig.ssid
        val password = wifiConfig.password
        val radarManager = RadarBleManager.getInstance(this)

        // 显示进度对话框
        showMessage("Connecting to device...")
        tvStatusOutput.text = "Starting wifi configuration..."
        //tvStatusOutput.text = "${tvStatusOutput.text}\nDevice connected successfully"
        val currentText = tvStatusOutput.text.toString()
        // 创建新的字符串并设置
        tvStatusOutput.text = getString(R.string.status_text_with_connection, currentText)


        radarManager.configureWifi(deviceAdd, ssidString, password) { result ->
            // 处理结果
            val success = result["success"]?.toBoolean() ?: false

            if (success) {
                configScan.saveWifiConfig(wifiConfig)
                // 更新状态输出
                //tvStatusOutput.text = "${tvStatusOutput.text}\nWiFi configuration successful"
                val deviceConnectedText = tvStatusOutput.text.toString()
                tvStatusOutput.text = getString(R.string.status_text_with_device_connected, deviceConnectedText)

                // 处理结果
                handleConfigResult(true)
                // 显示弹窗提示
                showMessage("WiFi configuration successful")
                // 检查是否需要配置服务器，增加时延到3秒
                if (getCurrentServerConfig() != null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        configureRadarServer()
                    }, 3000) // 3秒延时
                }
            }else{
                // 更新状态输出
                //tvStatusOutput.text = "${tvStatusOutput.text}\nWiFi configuration failed"
                val wifiConfigText = tvStatusOutput.text.toString()
                tvStatusOutput.text = getString(R.string.status_text_with_wifi_success, wifiConfigText)
                // 显示弹窗提示
                showMessage("WiFi configuration failed")
            }
        }
    }

    /**
     * A厂(Radar)服务器配置实现
     */
    @SuppressLint("MissingPermission", "SetTextI18x")
    private fun configureRadarServer() {
        val device = selectedDevice ?: return
        val serverConfig = getCurrentServerConfig() ?: return
        val radarManager = RadarBleManager.getInstance(this)

        // 显示进度对话框
        showMessage("Connecting to device...")
        tvStatusOutput.text = "Starting server configuration..."

        // 配置服务器 - 移除嵌套调用
        radarManager.configureServer(device, serverConfig) { result ->
            // 处理结果
            val success = result["success"]?.toBoolean() ?: false

            //tvStatusOutput.text = "${tvStatusOutput.text}\nServer configuration ${if (success) "successful" else "failed"}!"
            val serverConfigText = tvStatusOutput.text.toString()
            if (success) {
                tvStatusOutput.text = getString(R.string.status_text_with_server_success, serverConfigText)
            } else {
                tvStatusOutput.text = getString(R.string.status_text_with_server_failed, serverConfigText)
            }

            if (success) {
                // 保存服务器配置
                configScan.saveServerConfig(serverConfig)
                // 处理结果
                handleConfigResult(true)
                // 显示弹窗提示
                showMessage("Server configuration successful")

            } else {
                showMessage("Server configuration failed")
            }
        }
    }

    /**
     * B厂(Sleepace)配网实现
     */
    @SuppressLint("MissingPermission")
    private fun startSleepConfig() {
        // 获取设备信息
        val deviceInfo = selectedDevice ?: return
        // 从 BleDeviceManager 获取设备对象
        val bleDevice = BleDeviceManager.getDeviceAs(deviceInfo.macAddress, com.sleepace.sdk.domain.BleDevice::class.java)
        if (bleDevice == null) {
            showMessage(getString(R.string.toast_invalid_device))
            return
        }

        // 验证并创建配置对象
        val serverConfig = getCurrentServerConfig()
        val wifiConfig = getCurrentWifiConfig()


        // 检查是否是UDP端口
        val portStr = etServerPort.text.toString().lowercase()
        if (portStr.startsWith("udp")) {
            showMessage("Invalid port, need tcp port")
            return
        }

        // 检查server and wifi 项都存在配置
        if (serverConfig == null || wifiConfig == null) {
            showMessage(getString(R.string.toast_config_required))
            return
        }

        val sleepaceManager = SleepaceBleManager.getInstance(this)

        // 显示配置进度
        showMessage("Configuring...")

        try {
            sleepaceManager.startConfig(
                bleDevice,
                etServerAddress.text.toString(),
                etServerPort.text.toString().replace("tcp", "").replace("TCP", "").toInt(),
                etWifiSsid.text.toString().trim().toByteArray(),
                etWifiPassword.text.toString()
            ) { callbackData ->
                runOnUiThread {
                    when (callbackData.status) {
                        StatusCode.SUCCESS -> {
                            hideMessage()
                            // 处理配置结果
                            handleConfigResult(true)
                            if (callbackData.result is DeviceInfo) {
                                val deviceInfo = callbackData.result as DeviceInfo
                                Log.d(TAG, "WiFi configuration successful - Device ID: ${deviceInfo.deviceId}")
                            }
                            showMessage("""
                                WiFi configuration successful.
                                  <-SleepBoard WiFi Light->
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
                            showMessage("Device disconnected, retrying...")
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
                                  <-SleepBoard WiFi Light->
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

    // region 设备状态查询
    private fun handleStatusClick() {
        if (selectedDevice == null) {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }

        when (selectedDevice?.productorName) {
            Productor.radarQL -> {
                showMessage("Querying device status...")
                queryRadarStatus()
            }
            Productor.sleepBoardHS -> {
                showMessage("Querying device status...")
                querySleepaceStatus()
            }
            Productor.espBle -> {
                showMessage("Querying device status...")
                queryRadarStatus()
            }
            else -> showMessage(getString(R.string.toast_unknown_device_type))
        }
    }


    //@SuppressLint("MissingPermission")
    private fun queryRadarStatus() {
        val deviceInfo = selectedDevice ?: return
        val deviceHistory = configScan.getDeviceHistories()
            .find { it.macAddress == deviceInfo.macAddress }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val info = StringBuilder().apply {
            append("Device Info:\n")
            append("  Device ID: ${deviceInfo.deviceId}\n")
            append("  MAC: ${deviceInfo.macAddress}\n")
            append("  BleRSSI: ${deviceInfo.rssi}dBm\n")
        }

        showMessage("Querying device status...")

        RadarBleManager.getInstance(this).queryDeviceStatus(deviceInfo) { status ->
            runOnUiThread {
                if (status.containsKey("error")) {
                    info.append("\nStatus Query Error: ${status["error"]}")
                } else {
                    info.append("\nCurrent Status:")

                    // UID
                    status["uid"]?.let { uid ->
                        info.append("\n  UID: $uid")
                    }

                    // WiFi状态
                    info.append("\n  WiFi:")
                    when (status["wifiOpMode"]) {
                        "STA" -> {
                            val connected = status["staConnected"]?.toBoolean() ?: false
                            if (connected) {
                                // 已连接到WiFi
                                info.append(" Connected")
                                status["staSSID"]?.let {
                                    info.append(" to '$it'")
                                }
                                status["staRssi"]?.let {
                                    info.append(" (Signal: ${it}dBm)")
                                }
                            } else {
                                // STA模式但未连接
                                info.append(" Disconnected")
                                // 显示上次配置的WiFi
                                deviceHistory?.let {
                                    info.append(" (Last Config: ${it.wifiSsid})")
                                }
                            }
                        }
                        "SOFTAP" -> {
                            // AP模式
                            info.append(" AP Mode")
                            status["apSSID"]?.let { info.append(" '$it'") }
                            status["apConnCount"]?.let {
                                info.append(" (Connections: $it)")
                            }
                        }
                        "STASOFTAP" -> {
                            // 混合模式
                            info.append(" STA+AP Mode")

                            // STA部分
                            val connected = status["staConnected"]?.toBoolean() ?: false
                            if (connected) {
                                info.append(" | STA: Connected")
                                status["staSSID"]?.let { info.append(" to '$it'") }
                                status["staRssi"]?.let { info.append(" (${it}dBm)") }
                            } else {
                                info.append(" | STA: Disconnected")
                            }

                            // AP部分
                            info.append(" | AP: '${status["apSSID"] ?: "Unknown"}'")
                            status["apConnCount"]?.let { info.append(" (Connections: $it)") }
                        }
                        else -> {
                            // 未知或无WiFi
                            if (deviceHistory != null) {
                                info.append(" ${deviceHistory.wifiSsid} (Last Config)")
                            } else {
                                info.append(" Unknown")
                            }
                        }
                    }

                    // 服务器连接状态
                    info.append("\n  Server Connected: ${status["serverConnected"] == "true"}")
                }

                // 历史服务器配置
                if (deviceHistory != null) {
                    info.append("\n\nHistory Server: ${deviceHistory.serverConfig?.serverAddress}:${deviceHistory.serverConfig?.protocol}${deviceHistory.serverConfig?.port}")
                    info.append("\nLast Config Time: ${dateFormat.format(Date(deviceHistory.configTime))}")
                }

                info.append("\n\nQuery Time: ${dateFormat.format(Date())}")

                tvStatusOutput.text = info.toString()
                hideMessage()
                showMessage(if (!status.containsKey("error")) "Query completed" else "Query failed")
            }
        }
    }


    private fun querySleepaceStatus() {
        val deviceInfo = selectedDevice ?: return
        // 从 BleDeviceManager 获取设备对象
        val bleDevice = BleDeviceManager.getDeviceAs(deviceInfo.macAddress, com.sleepace.sdk.domain.BleDevice::class.java) ?: run {
            showMessage(getString(R.string.toast_invalid_device))
            return
        }

        // 从历史记录中查找该设备的配置
        val deviceHistory = configScan.getDeviceHistories()
            .find { it.macAddress == deviceInfo.macAddress }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val info = StringBuilder().apply {
            append("Device Info:\n")
            append("  Device ID: ${deviceInfo.deviceId}\n")
            append("  MAC: ${deviceInfo.macAddress}\n")
            append("  RSSI: ${deviceInfo.rssi}dBm\n")
            append("  VersionCode: ${bleDevice.versionCode}\n")

            append("\ndeviceHistory :\n")
            if (deviceHistory != null) {
                val configTimeStr = dateFormat.format(Date(deviceHistory.configTime))
                append("  Mode: Station\n")
                append("  SSID: ${deviceHistory.wifiSsid}\n")
                append("  Server: ${deviceHistory.serverConfig?.serverAddress}:${deviceHistory.serverConfig?.protocol}${deviceHistory.serverConfig?.port}\n")
                append("  Config Time: $configTimeStr\n")
            } else {
                append("  Not Configured\n")
            }
        }.toString()

        tvStatusOutput.text = info
        showMessage("Query completed")
    }

    // endregion

    // region 配置验证和保存
    /**
     * 解析协议和端口
     * @param input 用户输入的端口字符串，可能包含协议前缀
     * @return 返回协议和端口的Pair，如果解析失败则返回null
     */
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

    /**
     * 验证服务器配置
     * @param address 服务器地址
     * @param portInput 端口输入字符串
     * @return 如果验证通过，返回ServerConfig对象；否则返回null
     */
    private fun validateAndCreateServerConfig(address: String, portInput: String): ServerConfig? {
        if (address.isEmpty()) return null

        val protocolAndPort = parseProtocolAndPort(portInput)
        return protocolAndPort?.let { (protocol, port) ->
            if (port > 0) {
                ServerConfig(address, port, protocol)
            } else null
        }
    }

    /**
     * 验证WiFi配置
     * @param ssid WiFi名称
     * @param password WiFi密码
     * @return 如果验证通过，返回WifiConfig对象；否则返回null
     */
    private fun validateAndCreateWifiConfig(ssid: String, password: String): WifiConfig? {
        return if (ssid.isNotEmpty()) {
            WifiConfig(ssid, password)
        } else null
    }

    /**
     * 保存当前配置
     */
    private fun saveCurrentConfig() {
        getCurrentServerConfig()?.let { configScan.saveServerConfig(it) }
        getCurrentWifiConfig()?.let { configScan.saveWifiConfig(it) }
    }

    /**
     * 获取当前UI上的服务器配置
     */
    private fun getCurrentServerConfig(): ServerConfig? {
        val address = etServerAddress.text.toString().trim()
        val portInput = etServerPort.text.toString().trim()
        return validateAndCreateServerConfig(address, portInput)
    }

    /**
     * 获取当前UI上的WiFi配置
     */
    private fun getCurrentWifiConfig(): WifiConfig? {
        val ssid = etWifiSsid.text.toString().trim()
        val password = etWifiPassword.text.toString()
        return validateAndCreateWifiConfig(ssid, password)
    }

    /**
     * 获取指定的服务器配置
     * 可在startRadarConfig/startSleepConfig中直接调用
     */
    private fun getServerConfig(address: String, portInput: String): ServerConfig? {
        return validateAndCreateServerConfig(address, portInput)
    }

    /**
     * 获取指定的WiFi配置
     * 可在startRadarConfig/startSleepConfig中直接调用
     */
    private fun getWifiConfig(ssid: String, password: String): WifiConfig? {
        return validateAndCreateWifiConfig(ssid, password)
    }

    // endregion

    // region 结果处理
    @SuppressLint("MissingPermission")
    private fun handleConfigResult(success: Boolean) {
        if (success) {
            // 获取设备的 MAC 地址和名称
            val deviceMac = selectedDevice?.macAddress ?: return
            val deviceName = selectedDevice?.deviceName ?: ""


            // 获取当前配置
            val serverConfig = getCurrentServerConfig()
            val wifiConfig = getCurrentWifiConfig()

            if (serverConfig != null || wifiConfig != null) {
                // 创建或更新设备历史记录
                val deviceHistory = DeviceHistory(
                    deviceName = deviceName,
                    macAddress = deviceMac,
                    rssi = selectedDevice?.rssi ?: -255,
                    serverConfig = serverConfig,
                    wifiSsid = wifiConfig?.ssid ?: ""
                )

                // 保存设备历史记录
                configScan.saveDeviceHistory(deviceHistory)
            }

            // 刷新UI显示
            loadRecentConfigs()

            showMessage(getString(R.string.toast_config_success))
        }
    }

    private fun showMessage(message: String) {
        runOnUiThread {
            // 将消息追加到 tvStatusOutput 文本框中
            val currentText = tvStatusOutput.text.toString()
            val newText = if (currentText.isEmpty()) {
                message
            } else {
                "$currentText\n$message"
            }
            tvStatusOutput.text = newText
        }
    }

    private fun hideMessage() {
    }

    // endregion




      fun testServerConfigLogic() {
        val configStorage = ConfigStorage(this)

        // 清除所有现有配置
        configStorage.clearServerConfigs()
        Log.d("ConfigTest", "已清除所有服务器配置")

        // 验证清除是否成功
        var configs = configStorage.getServerConfigs()
        Log.d("ConfigTest", "清除后配置数量: ${configs.size}")

        // 添加5个测试配置
        for (i in 1..5) {
            val config = ServerConfig(
                serverAddress = "server$i.example.com",
                port = 1000 + i,
                protocol = "tcp",
                timestamp = System.currentTimeMillis() + i
            )
            configStorage.saveServerConfig(config)

            // 检查添加后的状态
            configs = configStorage.getServerConfigs()
            Log.d("ConfigTest", "添加#${i}后，配置数量: ${configs.size}")
            Log.d("ConfigTest", "当前配置列表: ${configs.map { "${it.serverAddress}:${it.port}" }}")
        }

        // 添加第6个配置，验证是否删除最后一个
        val extraConfig = ServerConfig(
            serverAddress = "extraserver.example.com",
            port = 2000,
            protocol = "tcp",
            timestamp = System.currentTimeMillis()
        )
        configStorage.saveServerConfig(extraConfig)

        // 检查是否正确删除了最后一个
        configs = configStorage.getServerConfigs()
        Log.d("ConfigTest", "添加第6个后，配置数量: ${configs.size}")
        Log.d("ConfigTest", "最终配置列表: ${configs.map { "${it.serverAddress}:${it.port}" }}")

        // 验证第一个是否是刚添加的
        if (configs.isNotEmpty() && configs[0].serverAddress == extraConfig.serverAddress) {
            Log.d("ConfigTest", "测试通过：最新添加的配置在列表首位")
        } else {
            Log.e("ConfigTest", "测试失败：最新添加的配置不在列表首位")
        }

        // 验证列表大小
        if (configs.size <= 5) {
            Log.d("ConfigTest", "测试通过：配置列表不超过5个")
        } else {
            Log.e("ConfigTest", "测试失败：配置列表超过5个")
        }
    }

}