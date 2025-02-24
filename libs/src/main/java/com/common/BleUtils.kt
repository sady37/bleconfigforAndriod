// BleUtils.kt
package com.common

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import com.common.BleAdvertiseData
import com.common.BleResult
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC
import android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC


@SuppressLint("MissingPermission")
object BleUtils {
    /**
     * 扩展函数：将系统的 ScanResult 转换为可序列化的 BleResult
     */

    fun android.bluetooth.le.ScanResult.toBleResult(): BleResult {
        val advertiseData = scanRecord?.let { record ->
            BleAdvertiseData(
                deviceName = record.deviceName,
                txPowerLevel = record.txPowerLevel ?: -1,
                advertiseFlags = record.advertiseFlags ?: 0,
                serviceUuids = record.serviceUuids?.map { it.toString() } ?: emptyList(),
                manufacturerData = (0 until (record.manufacturerSpecificData?.size() ?: 0))
                    .associate { i ->
                        val key = record.manufacturerSpecificData!!.keyAt(i)
                        val value = record.manufacturerSpecificData!!.valueAt(i)
                        key to value
                    },
                serviceData = record.serviceData?.mapKeys { it.key.toString() } ?: emptyMap(),
                isConnectable = isConnectable
            )
        }

        return BleResult(
            deviceName = device.name,
            deviceAddress = device.address,
            rssi = rssi,
            timestamp = timestampNanos,
            deviceType = device.type,
            addressType = 0,
            advertiseData = advertiseData,
            periodicAdvertisingInterval = periodicAdvertisingInterval,
            primaryPhy = primaryPhy,
            secondaryPhy = secondaryPhy,
            advertisingSid = advertisingSid,
            txPower = txPower,
            isLegacy = isLegacy,
            isConnectable = isConnectable,
            dataStatus = dataStatus
        )
    }
}