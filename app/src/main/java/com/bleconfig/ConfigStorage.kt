package com.bleconfig

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConfigStorage(context: Context) {
    private val prefs = context.getSharedPreferences("ble_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 保存服务器配置
    fun saveServerConfig(serverConfig: ServerConfig) {
        val servers = getServerConfigs().toMutableList()

        // 移除相同配置
        servers.removeAll { it.serverAddress == serverConfig.serverAddress && it.port == serverConfig.port }

        // 添加到开头
        servers.add(0, serverConfig)

        // 保持最多 5 条记录
        if (servers.size > 5) {
            servers.removeAt(servers.lastIndex)
        }

        // 保存到 SharedPreferences
        prefs.edit().apply {
            putString(KEY_SERVER_CONFIGS, gson.toJson(servers))
            apply()
        }
    }

    // 获取服务器配置
    fun getServerConfigs(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVER_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    // 保存 Wi-Fi 配置
    fun saveWifiConfig(wifiConfig: WifiConfig) {
        val wifis = getWifiConfigs().toMutableList()

        // 移除相同配置
        wifis.removeAll { it.ssid == wifiConfig.ssid }

        // 添加到开头
        wifis.add(0, wifiConfig)

        // 保持最多 5 条记录
        if (wifis.size > 5) {
            wifis.removeAt(wifis.lastIndex)
        }

        // 保存到 SharedPreferences
        prefs.edit().apply {
            putString(KEY_WIFI_CONFIGS, gson.toJson(wifis))
            apply()
        }
    }

    // 获取 Wi-Fi 配置
    fun getWifiConfigs(): List<WifiConfig> {
        val json = prefs.getString(KEY_WIFI_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<WifiConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    // 保存配网成功的设备记录
    fun saveDeviceHistory(deviceHistory: DeviceHistory) {
        val histories = getDeviceHistories().toMutableList()

        // 移除相同设备的记录
        histories.removeAll { it.macAddress == deviceHistory.macAddress }

        // 添加到开头
        histories.add(0, deviceHistory)

        // 保持最多 100 条记录
        if (histories.size > 100) {
            histories.removeAt(histories.lastIndex)
        }

        // 保存到 SharedPreferences
        prefs.edit().apply {
            putString(KEY_DEVICE_HISTORIES, gson.toJson(histories))
            apply()
        }
    }

    // 获取配网成功的设备记录
    fun getDeviceHistories(): List<DeviceHistory> {
        val json = prefs.getString(KEY_DEVICE_HISTORIES, "[]") ?: "[]"
        val type = object : TypeToken<List<DeviceHistory>>() {}.type
        return gson.fromJson(json, type)
    }

    companion object {
        private const val KEY_SERVER_CONFIGS = "server_configs"
        private const val KEY_WIFI_CONFIGS = "wifi_configs"
        private const val KEY_DEVICE_HISTORIES = "device_histories"
    }
}