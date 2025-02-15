package com.bleconfig

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import com.sleepace.sdk.domain.BleDevice
import java.io.Serializable
import com.sleepace.sdk.wificonfig.WiFiConfigHelper
import com.sleepace.sdk.interfs.IResultCallback
import com.sleepace.sdk.manager.CallbackData
import com.sleepace.sdk.constant.StatusCode
import com.sleepace.sdk.util.SdkLog


// 设备类型枚举
enum class DeviceType {
    RADAR,       // A厂(雷达)设备
    SLEEPBOARD   // B厂(睡眠板)设备
}

// 服务器配置
data class ServerConfig(
    val serverAddress: String, // 服务器地址
    val port: Int,             // 服务器端口
    val protocol: String = "TCP", // 协议类型，默认 TCP
    val timestamp: Long = System.currentTimeMillis()  // 配置时间戳
) : Serializable

// WiFi配置
data class WifiConfig(
    val ssid: String,          // Wi-Fi SSID
    val password: String,      // Wi-Fi 密码
    val timestamp: Long = System.currentTimeMillis()  // 配置时间戳
) : Serializable

// 设备配网历史
data class DeviceHistory(
    val deviceType: DeviceType,  // 设备类型
    val macAddress: String,     // 设备 MAC 地址
    val rssi: Int,              // 信号强度
    val configTime: Long = System.currentTimeMillis(),  // 配网时间
    val serverConfig: ServerConfig,  // 服务器配置
    val wifiConfig: WifiConfig       // Wi-Fi 配置
) : Serializable

// 统一的扫描结果数据结构
data class DeviceInfo(
    val type: DeviceType,      // 设备类型
    val name: String,          // 设备名称
    val macAddress: String,    // 设备 MAC 地址
    val rssi: Int,             // 信号强度
    val bleDevice: BleDevice? = null  // B厂设备对象（可选）
) : Serializable

// A 厂设备状态（补充）
data class RadarDeviceStatus(
    val isDetecting: Boolean,  // 是否正在检测
    val sensitivity: Int,      // 当前灵敏度
    val mode: Int,             // 当前工作模式
    val errorCode: Int         // 错误码
) : Serializable

// B 厂设备信息（补充）
data class SleepBoardDeviceInfo(
    val deviceId: String? = null  // 设备 ID（可选）
) : Serializable