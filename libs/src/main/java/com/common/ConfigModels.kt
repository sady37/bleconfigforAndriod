/**
 * File: ConfigModels.kt
 * Package: com.wisefido
 *
 * 数据模型定义：
 * 1. BLE基础数据类型
 * 2. 服务器配置模型
 * 3. WiFi配置模型
 * 4. 设备相关模型
 * 5. 厂商特定模型
 */
package com.common

import java.io.Serializable

/**
 * ===== 1. Android 蓝牙底层扩展 =====
 */

/**
 * ===== 2. ESP 芯片层扩展 =====
 */
data class EspWifiConfig(
    val ssid: String,        // WiFi SSID
    val password: String,    // WiFi 密码
    val mode: Int,           // 0:NULL, 1:STA, 2:SOFTAP, 3:STASOFTAP
    val security: Int        // 0:OPEN, 1:WEP, 2:WPA, 3:WPA2, 4:WPA_WPA2
) : Serializable

data class EspConfigResult(
    val status: Int,         // 配置结果状态码
    val message: String,     // 结果描述
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * ===== 3. 设备厂家层 =====
 */
// 生产厂家枚举
enum class Productor {
    radarQL,      // A厂雷达
    sleepBoardHS, // B厂睡眠板
    espBle        // 通用蓝牙设备
}

// 过滤器类型枚举
enum class FilterType {
    DEVICE_NAME,  // 设备名过滤
    MAC,          // MAC地址过滤
    UUID;         // UUID过滤
}

// A. 通用配置

// 服务器配置
data class ServerConfig(
    val serverAddress: String, // 服务器地址
    val port: Int,             // 服务器端口
    val protocol: String = "TCP", // 协议类型，默认 TCP
    val timestamp: Long = System.currentTimeMillis()  // 配置时间戳
) : Serializable

// WiFi配置 (应用层)
data class WifiConfig(
    val ssid: String,          // Wi-Fi SSID
    val password: String,      // Wi-Fi 密码
    val timestamp: Long = System.currentTimeMillis()  // 配置时间戳
) : Serializable

// 配网历史
data class DeviceHistory(
    val deviceName: String,  // 设备类型
    val macAddress: String,     // 设备 MAC 地址
    val rssi: Int,              // 信号强度
    val configTime: Long = System.currentTimeMillis(),  // 配网时间
    val serverConfig: ServerConfig,  // 服务器配置
    val wifiConfig: WifiConfig       // Wi-Fi 配置
) : Serializable

// 统一的扫描结果
data class DeviceInfo(
    val productorName: Productor,    //产品厂家如radarQL, sleepBoardHS
    val deviceName: String,      // 设备类型,扫描出来的
    val deviceId: String,      // 设备ID - 用于显示的设备标识
    val macAddress: String,    // MAC地址 - 用于匹配历史配网记录
    val rssi: Int = -255,            // 信号强度,默认值 =-255即没有获取
    val originalDevice: Any?   // 原始设备对象 - 用于配网
) : Serializable

// B. A厂(雷达)专有数据结构

// 雷达设备状态
data class RadarDeviceStatus(
    val isDetecting: Boolean,  // 是否正在检测
    val sensitivity: Int,      // 当前灵敏度
    val mode: Int,             // 当前工作模式
    val errorCode: Int         // 错误码
) : Serializable

// 雷达配置参数
data class RadarConfig(
    val sensitivity: Int,     // 灵敏度设置(0-100)
    val mode: Int,           // 工作模式
    val interval: Int,       // 数据上报间隔(ms)
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

// C. B厂(sleepace)专有数据结构

// 睡眠板设备信息
data class SleepBoardDeviceInfo(
    val deviceId: String,      // 设备 ID
    val model: String? = null, // 型号
    val version: String? = null // 版本号
) : Serializable

// 睡眠板配置参数
data class SleepBoardConfig(
    val sampleRate: Int,      // 采样率
    val mode: Int,            // 工作模式
    val threshold: Int,       // 阈值
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * 状态码定义
 */
/*
object StatusCode {
    const val SUCCESS: Short = 0
    const val DISCONNECT: Short = -1
    const val TIMEOUT: Short = -2
    const val FAIL: Short = -3
    const val NOT_ENABLE: Short = -4
    const val PARAMETER_ERROR: Short = -5
    const val CERTIFICATION_EXPIRED: Short = -6
}
*/


/**
 * 默认配置值
 */
object DefaultConfig {
    const val RADAR_DEVICE_NAME = "TSBLU" // 默认雷达设备名称
    val DEFAULT_FILTER_TYPE = FilterType.DEVICE_NAME // 默认过滤器类型
    const val DEFAULT_FILTER_PREFIX = "" // 默认过滤器前缀
}

// RequestCodes
object RequestCodes {
    const val PERMISSION_REQUEST = 100
    const val SCAN_SLEEPACE = 200
    const val SCAN_RADAR = 201
    // ... 其他请求码
}