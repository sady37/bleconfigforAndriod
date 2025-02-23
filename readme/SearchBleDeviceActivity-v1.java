/**
 * File 1: SearchBleDeviceActivity.java
 * Package: com.bleconfig.sleepace
 * 
 * 修改说明：
 * 1. 改进扫描回调逻辑
 * 2. 加强设备名称验证
 * 3. 优化日志记录
 * 4. 添加错误处理
 */
package com.bleconfig.sleepace;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.sleepace.sdk.domain.BleDevice;
import com.sleepace.sdk.interfs.IBleScanCallback;
import com.sleepace.sdk.manager.ble.BleHelper;
import com.sleepace.sdk.util.SdkLog;

public class SearchBleDeviceActivity extends Activity {
    private static final String TAG = "SearchBleDeviceActivity";
    private BleHelper bleHelper;
    public static final String EXTRA_DEVICE = "extra_device";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bleHelper = BleHelper.getInstance(this);
        startScan();
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if(bleHelper.isBluetoothOpen()) {
            bleHelper.scanBleDevice(scanCallback);
        }
    }

    // [修改] 改进扫描回调逻辑
    private IBleScanCallback scanCallback = new IBleScanCallback() {
        @Override
        public void onStartScan() {
            Log.d(TAG, "Started scanning for BLE devices");
        }

        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
            // [修改] 解析设备名称前记录原始数据
            String originalName = device.getName();
            Log.d(TAG, "Raw scan result - device: " + originalName + ", address: " + device.getAddress());

            // [修改] 从广播数据中获取设备名称
            String deviceName = BleDeviceValidator.parseDeviceName(scanRecord);
            if (deviceName != null) {
                deviceName = deviceName.trim();
            }
            SdkLog.log(TAG + " onLeScan deviceName:" + deviceName);

            // [修改] 设备名称验证
            if (!BleDeviceValidator.isValidDeviceName(deviceName)) {
                Log.d(TAG, "Invalid device name: " + deviceName);
                return;
            }

            // [修改] 检查是否为 BM8701 设备
            if (!BleDeviceValidator.isBM8701Device(deviceName)) {
                Log.d(TAG, "Not a BM8701 device: " + deviceName);
                return;
            }

            // [修改] 创建设备对象
            BleDevice bleDevice = BleDeviceValidator.createBleDevice(deviceName, device.getAddress());
            if (bleDevice == null) {
                Log.e(TAG, "Failed to create device object");
                return;
            }

            // [修改] 返回扫描结果
            runOnUiThread(() -> {
                bleHelper.stopScan();
                Intent data = new Intent();
                data.putExtra(EXTRA_DEVICE, bleDevice);
                setResult(RESULT_OK, data);
                finish();
            });
        }

        @Override
        public void onStopScan() {
            Log.d(TAG, "Scan stopped");
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleHelper.stopScan();
    }
}

/**
 * File 2: BleDeviceValidator.java
 * Package: com.bleconfig.sleepace.util
 * 
 * 修改说明：
 * 1. 新增设备验证工具类
 * 2. 实现设备名称解析和验证
 * 3. 实现 BM8701 设备检查
 * 4. 实现设备对象创建
 */
package com.bleconfig.sleepace.util;

import android.util.Log;
import com.sleepace.sdk.domain.BleDevice;
import com.sleepace.sdk.manager.DeviceType;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

public class BleDeviceValidator {
    private static final String TAG = "BleDeviceValidator";

    // [新增] 从广播数据中解析设备名称
    public static String parseDeviceName(byte[] scanRecord) {
        if (scanRecord == null) return null;

        try {
            String deviceName = BleDeviceNameUtil.getBleDeviceName(0xff, scanRecord);
            return deviceName != null ? deviceName.trim() : null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing device name", e);
            return null;
        }
    }

    // [新增] 验证设备名称是否有效
    public static boolean isValidDeviceName(String deviceName) {
        if (deviceName == null) return false;

        // 检查是否包含非 ASCII 字符
        byte[] bytes = deviceName.getBytes(StandardCharsets.US_ASCII);
        String converted = new String(bytes, StandardCharsets.US_ASCII);
        if (!deviceName.equals(converted)) {
            return false;
        }

        // [修改] 设备名称格式检查
        return Pattern.matches("^[A-Za-z0-9-]+$", deviceName);
    }

    // [新增] 检查是否为 BM8701 设备
    public static boolean isBM8701Device(String deviceName) {
        if (deviceName == null) return false;

        // [修改] 支持两种命名格式
        // 1. 旧格式: BM + 11位字符 (例如: BM12345678901)
        // 2. 新格式: BM87 + 8位数字 (例如: BM87224601641)
        return Pattern.matches("^BM[0-9A-Za-z-]{11}$", deviceName) ||  // 旧格式
               Pattern.matches("^BM87[0-9]{8}$", deviceName);          // 新格式
    }

    // [新增] 创建设备对象
    public static BleDevice createBleDevice(String deviceName, String address) {
        if (!isValidDeviceName(deviceName) || address == null) {
            return null;
        }

        BleDevice device = new BleDevice();
        device.setDeviceName(deviceName);
        device.setDeviceId(deviceName);
        device.setAddress(address);
        device.setModelName(deviceName);

        // [修改] 设置设备类型
        if (isBM8701Device(deviceName)) {
            device.setDeviceType(DeviceType.DEVICE_TYPE_BM8701);
            Log.d(TAG, "Created BM8701 device: " + deviceName);
        } else {
            // [修改] 即使不是 BM8701 设备也返回对象，由调用方决定是否使用
            Log.d(TAG, "Created device without type: " + deviceName);
        }

        return device;
    }
}