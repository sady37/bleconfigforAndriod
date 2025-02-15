package com.bleconfig


import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import android.graphics.drawable.VectorDrawable;

class MainActivity : AppCompatActivity() {

    private lateinit var btnScan: MaterialButton
    private lateinit var etServerAddress: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var etWifiSsid: TextInputEditText
    private lateinit var etWifiPassword: TextInputEditText
    private lateinit var btnPair: MaterialButton
    private lateinit var tvRecentServer: MaterialTextView
    private lateinit var tvRecentWifi: MaterialTextView

    // 历史记录UI元素
    private lateinit var layoutServerHistory: View
    private lateinit var layoutWifiHistory: View

    private lateinit var configStorage: ConfigStorage

    // 当前选中的设备信息
    private var deviceType: String? = null
    private var deviceId: String? = null
    private var deviceMac: String? = null

    private fun parseProtocolAndPort(input: String): Pair<String, Int>? {
        val regex = Regex("^(tcp|udp)?(\\d+)$", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(input)

        return if (matchResult != null) {
            val protocol = matchResult.groupValues[1].lowercase().ifEmpty { "tcp" }
            val port = matchResult.groupValues[2].toIntOrNull()

            if (port != null && port in 1..65535) {
                Pair(protocol, port)
            } else {
                null // Invalid port number
            }
        } else {
            null // Input doesn't match the expected format
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configStorage = ConfigStorage(this)
        initViews()
        setupHistoryViews()
        loadRecentConfigs()
    }

    private fun initViews() {
        btnScan = findViewById(R.id.btn_scan)
        etServerAddress = findViewById(R.id.et_server_address)
        etServerPort = findViewById(R.id.et_server_port)
        etWifiSsid = findViewById(R.id.et_wifi_ssid)
        etWifiPassword = findViewById(R.id.et_wifi_password)
        btnPair = findViewById(R.id.btn_pair)
        tvRecentServer = findViewById(R.id.tv_recent_server)
        tvRecentWifi = findViewById(R.id.tv_recent_wifi)

        // 初始化历史记录视图
        layoutServerHistory = findViewById(R.id.layout_server_history)
        layoutWifiHistory = findViewById(R.id.layout_wifi_history)

        btnScan.setOnClickListener {
            startScanActivity()
        }

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
    }

    private fun setupHistoryViews() {
        val clockDrawable = ContextCompat.getDrawable(this, R.drawable.ic_history_24)
        clockDrawable?.setTint(ContextCompat.getColor(this, R.color.text_secondary))
        tvRecentServer.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
        tvRecentWifi.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
    }

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
            tvRecentServer.text = "Recent Servers (${recentServers.size.coerceAtMost(5)})"
            layoutServerHistory.visibility = View.VISIBLE
        } else {
            layoutServerHistory.visibility = View.GONE
        }

        val recentWifis = configStorage.getWifiConfigs()
        if (recentWifis.isNotEmpty()) {
            tvRecentWifi.text = "Recent Networks (${recentWifis.size.coerceAtMost(5)})"
            layoutWifiHistory.visibility = View.VISIBLE
        } else {
            layoutWifiHistory.visibility = View.GONE
        }
    }

    private fun startScanActivity() {
        saveCurrentConfig()
        val intent = Intent(this, ScanActivity::class.java)
        startActivityForResult(intent, REQUEST_SCAN_DEVICE)
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

        // 根据设备类型调用对应的配网模块
        when (deviceType) {
            "RADAR" -> startRadarConfig() // 调用 A 厂模块进行配网
            "SLEEP" -> startSleepConfig()  // 调用 B 厂模块进行配网
            else -> showToast(getString(R.string.toast_unknown_device_type))
        }
    }

    private fun startRadarConfig() {
        val intent = Intent("com.radar.CONFIG_ACTION").apply {
            putExtra("device_mac", deviceMac)
            putExtra("server_address", etServerAddress.text.toString())
            putExtra("server_port", etServerPort.text.toString())
            putExtra("wifi_ssid", etWifiSsid.text.toString())
            putExtra("wifi_password", etWifiPassword.text.toString())
        }
        startActivityForResult(intent, REQUEST_RADAR_CONFIG)
    }

    private fun startSleepConfig() {
        val intent = Intent("com.sleepboard.CONFIG_ACTION").apply {
            putExtra("device_mac", deviceMac)
            putExtra("server_address", etServerAddress.text.toString())
            putExtra("server_port", etServerPort.text.toString())
            putExtra("wifi_ssid", etWifiSsid.text.toString())
            putExtra("wifi_password", etWifiPassword.text.toString())
        }
        startActivityForResult(intent, REQUEST_SLEEP_CONFIG)
    }

    private fun saveCurrentConfig() {
        val serverAddress = etServerAddress.text.toString()
        val portInput = etServerPort.text.toString()
        val wifiSsid = etWifiSsid.text.toString()
        val wifiPassword = etWifiPassword.text.toString()

        val protocolAndPort = parseProtocolAndPort(portInput)
        if (protocolAndPort != null) {
            val (protocol, port) = protocolAndPort
            if (serverAddress.isNotEmpty() && port > 0) {
                configStorage.saveServerConfig(ServerConfig(serverAddress, port, protocol))
            }
        }

        if (wifiSsid.isNotEmpty() && wifiPassword.isNotEmpty()) {
            configStorage.saveWifiConfig(WifiConfig(wifiSsid, wifiPassword))
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_SCAN_DEVICE -> {
                    data?.let {
                        deviceType = it.getStringExtra("device_type")
                        deviceId = it.getStringExtra("device_id")
                        deviceMac = it.getStringExtra("device_mac")
                        btnScan.text = deviceId ?: getString(R.string.hint_select_device)
                    }
                }
                REQUEST_RADAR_CONFIG, REQUEST_SLEEP_CONFIG -> {
                    val success = data?.getBooleanExtra("config_success", false) ?: false
                    if (success) {
                        deviceMac?.let { mac ->
                            val portInput = etServerPort.text.toString()
                            val protocolAndPort = parseProtocolAndPort(portInput)
                            if (protocolAndPort != null) {
                                val (protocol, port) = protocolAndPort
                                configStorage.saveDeviceHistory(
                                    DeviceHistory(
                                        deviceType = DeviceType.valueOf(deviceType ?: "RADAR"),
                                        macAddress = mac,
                                        rssi = -60, // 默认值，实际应从扫描结果中获取
                                        serverConfig = ServerConfig(
                                            etServerAddress.text.toString(),
                                            port,
                                            protocol
                                        ),
                                        wifiConfig = WifiConfig(
                                            etWifiSsid.text.toString(),
                                            etWifiPassword.text.toString()
                                        )
                                    )
                                )
                            }
                        }
                        loadRecentConfigs() // 更新历史记录显示
                        showToast(getString(R.string.toast_config_success))
                    } else {
                        showToast(getString(R.string.toast_config_failed))
                    }
                }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_SCAN_DEVICE = 1001 // 设备扫描请求码
        private const val REQUEST_RADAR_CONFIG = 1002 // A 厂模块配网请求码
        private const val REQUEST_SLEEP_CONFIG = 1003 // B 厂模块配网请求码
    }
}