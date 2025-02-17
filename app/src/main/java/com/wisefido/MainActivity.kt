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
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
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

// A厂 SDK
import com.espressif.espblufi.params.BlufiConfigureParams
import com.espressif.espblufi.params.BlufiParameter
import com.espressif.espblufi.RadarBleManager

// B厂 SDK
import com.bleconfig.sleepace.SleePaceBleManager  // B厂管理类
import com.sleepace.sdk.constant.StatusCode
import com.sleepace.sdk.domain.BleDevice
import com.sleepace.sdk.manager.DeviceType

import android.bluetooth.le.ScanResult  // A 厂扫描结果

//自已的引用
import com.wisefido.ConfigStorage


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // region 属性定义
    private lateinit var btnScan: MaterialButton
    private lateinit var etServerAddress: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var etWifiSsid: TextInputEditText
    private lateinit var etWifiPassword: TextInputEditText
    private lateinit var btnPair: MaterialButton
    private lateinit var tvRecentServer: MaterialTextView
    private lateinit var tvRecentWifi: MaterialTextView

    private lateinit var layoutServerHistory: View
    private lateinit var layoutWifiHistory: View

    private lateinit var configStorage: ConfigStorage
    private var lastScannedBleDevice: BleDevice? = null


    // 当前选中的设备信息
    private var deviceType: String? = null
    private var deviceId: String? = null
    private var deviceMac: String? = null

    // Activity Result API
// 扫描结果处理需要修改为：
    @SuppressLint("MissingPermission")
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { intent ->
                deviceType = intent.getStringExtra(ScanActivity.EXTRA_DEVICE_TYPE)
                // 根据设备类型获取对应的设备对象
                when (deviceType) {
                    "radarQL", "ESP" -> {
                        val scanResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(ScanActivity.EXTRA_DEVICE, ScanResult::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(ScanActivity.EXTRA_DEVICE)
                        }
                        deviceId = scanResult?.device?.name
                        deviceMac = scanResult?.device?.address
                    }
                    "sleepace" -> {
                        val bleDevice = intent.getSerializableExtra(ScanActivity.EXTRA_DEVICE) as? BleDevice
                        deviceId = bleDevice?.deviceName
                        deviceMac = bleDevice?.address
                        lastScannedBleDevice = bleDevice
                    }
                }
                btnScan.text = deviceId ?: getString(R.string.hint_select_device)
            }
        }
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

        configStorage = ConfigStorage(this)
        initViews()
        setupHistoryViews()
        loadRecentConfigs()
    }
    // endregion

    // region UI初始化
    private fun initViews() {
        btnScan = findViewById(R.id.btn_scan)
        etServerAddress = findViewById(R.id.et_server_address)
        etServerPort = findViewById(R.id.et_server_port)
        etWifiSsid = findViewById(R.id.et_wifi_ssid)
        etWifiPassword = findViewById(R.id.et_wifi_password)
        btnPair = findViewById(R.id.btn_pair)
        tvRecentServer = findViewById(R.id.tv_recent_server)
        tvRecentWifi = findViewById(R.id.tv_recent_wifi)

        layoutServerHistory = findViewById(R.id.layout_server_history)
        layoutWifiHistory = findViewById(R.id.layout_wifi_history)

        btnScan.setOnClickListener {
            startScanActivity()
        }

        btnPair.setOnClickListener {
            handlePairClick()
        }

        layoutServerHistory.setOnClickListener {
            showServerHistoryMenu(it)
        }

        layoutWifiHistory.setOnClickListener {
            showWifiHistoryMenu(it)
        }
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
        val recentServers = configStorage.getServerConfigs().take(5)
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
        val recentWifis = configStorage.getWifiConfigs().take(5)
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
        val recentServers = configStorage.getServerConfigs()
        if (recentServers.isNotEmpty()) {
            tvRecentServer.text = getString(R.string.recent_servers_count, recentServers.size.coerceAtMost(5))
            layoutServerHistory.visibility = View.VISIBLE
        } else {
            layoutServerHistory.visibility = View.GONE
        }

        val recentWifis = configStorage.getWifiConfigs()
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
        saveCurrentConfig()
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    private fun handlePairClick() {
        saveCurrentConfig()

        if (!validateInput()) {
            return
        }

        if (deviceType == null || deviceMac == null) {
            showToast(getString(R.string.toast_select_device_first))
            return
        }

        when (deviceType) {
            "radarQL" -> startRadarConfig()
            "sleepace" -> startSleepConfig()
            else -> showToast(getString(R.string.toast_unknown_device_type))
        }
    }

    /**
     * A厂(Radar)配网实现
     */
    private fun startRadarConfig() {
        val radarManager = RadarBleManager.getInstance(this)
        val serverConfig = getCurrentServerConfig() ?: return
        val wifiConfig = getCurrentWifiConfig() ?: return

        // 1. 创建配置参数
        val params = BlufiConfigureParams().apply {
            opMode = BlufiParameter.OP_MODE_STA  // Station模式
            setStaSSIDBytes(wifiConfig.ssid.toByteArray())  // 使用setStaSSIDBytes并转换为字节数组
            setStaPassword(wifiConfig.password)
        }

        // 2. 连接设备
        val device = BluetoothAdapter.getDefaultAdapter()
            ?.getRemoteDevice(deviceMac) ?: return

        // 3. 开始配网
        radarManager.connect(device)
        radarManager.configure(params) { success ->
            runOnUiThread {
                handleConfigResult(success)
            }
        }
    }

    private fun startSleepConfig() {
        val bleDevice = lastScannedBleDevice
        if (bleDevice == null) {
            showToast(getString(R.string.toast_invalid_device))
            return
        }

        // 开始配网
        val sleepaceManager = SleePaceBleManager.getInstance(this)
        try {
            sleepaceManager.startConfig(
                bleDevice,
                etServerAddress.text.toString(),
                etServerPort.text.toString().toInt(),
                getWifiSsidRawData(),
                etWifiPassword.text.toString()
            ) { callbackData ->
                runOnUiThread {
                    val success = callbackData.status == StatusCode.SUCCESS
                    if (!success) {
                        Log.e(TAG, "Config failed with status: ${callbackData.status}")
                    }
                    handleConfigResult(success)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Sleepace config", e)
            showToast(getString(R.string.toast_config_exception))
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

        serverConfig?.let { configStorage.saveServerConfig(it) }
        wifiConfig?.let { configStorage.saveWifiConfig(it) }
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
            showToast(getString(R.string.toast_enter_server_address))
            return false
        }

        val portInput = etServerPort.text.toString()
        if (portInput.isEmpty()) {
            showToast(getString(R.string.toast_enter_server_port))
            return false
        }

        val protocolAndPort = parseProtocolAndPort(portInput)
        if (protocolAndPort == null) {
            showToast(getString(R.string.toast_invalid_port_format))
            return false
        }

        if (etWifiSsid.text.isNullOrEmpty()) {
            showToast(getString(R.string.toast_enter_wifi_name))
            return false
        }

        if (etWifiPassword.text.isNullOrEmpty()) {
            showToast(getString(R.string.toast_enter_wifi_password))
            return false
        }

        return true
    }
    // endregion

    // region 结果处理
    private fun handleConfigResult(success: Boolean) {
        if (success) {
            deviceMac?.let { mac ->
                getCurrentServerConfig()?.let { serverConfig ->
                    getCurrentWifiConfig()?.let { wifiConfig ->
                        configStorage.saveDeviceHistory(
                            DeviceHistory(
                                deviceType = com.wisefido.DeviceType.valueOf(deviceType ?: "radarQL"),  // 使用我们自己的 DeviceTyp
                                macAddress = mac,
                                rssi = -60,
                                serverConfig = serverConfig,
                                wifiConfig = wifiConfig
                            )
                        )
                    }
                }
            }
            loadRecentConfigs()
            showToast(getString(R.string.toast_config_success))
        } else {
            showToast(getString(R.string.toast_config_failed))
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    // endregion

}