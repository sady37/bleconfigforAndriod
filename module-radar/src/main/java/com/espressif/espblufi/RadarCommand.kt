package com.espressif.espblufi

/**
 * 雷达设备的命令枚举
 */
enum class RadarCommand(val code: Int) {
    GET_VERSION(0x01),        // 获取版本信息
    GET_STATUS(0x02),         // 获取设备状态
    START_DETECT(0x03),       // 开始检测
    STOP_DETECT(0x04),        // 停止检测
    SET_SENSITIVITY(0x05),    // 设置灵敏度
    SET_MODE(0x06),          // 设置工作模式
    FACTORY_RESET(0x07),     // 恢复出厂设置
    GET_CONFIG(0x08),        // 获取配置
    SET_CONFIG(0x09);        // 设置配置

    companion object {
        fun fromCode(code: Int): RadarCommand? = values().find { it.code == code }
    }

    /**
     * 将命令转换为字节数组用于发送
     */
    fun toBytes(): ByteArray {
        // 命令格式: [命令码, 数据长度, 数据内容(可选), 校验和]
        return byteArrayOf(
            0xAA.toByte(),      // 起始帧
            code.toByte(),      // 命令码
            0x00,               // 数据长度 (默认为0)
            calculateChecksum(code) // 校验和
        )
    }

    /**
     * 带参数的命令转换
     */
    fun toBytes(params: ByteArray): ByteArray {
        return byteArrayOf(
            0xAA.toByte(),           // 起始帧
            code.toByte(),           // 命令码
            params.size.toByte(),    // 数据长度
            *params,                 // 数据内容
            calculateChecksum(code, params) // 校验和
        )
    }

    private fun calculateChecksum(code: Int, params: ByteArray = ByteArray(0)): Byte {
        var sum = 0xAA + code + params.size
        params.forEach { sum += it }
        return (sum and 0xFF).toByte()
    }
}