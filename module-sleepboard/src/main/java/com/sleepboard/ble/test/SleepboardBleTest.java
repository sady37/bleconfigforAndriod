// 文件路径: module-sleepboard/src/main/java/com/sleepboard/ble/SleepboardBleTest.java

package com.sleepboard.ble.test;

import android.annotation.SuppressLint;
import android.content.Context;
import android.bluetooth.BluetoothDevice;
import com.sleepace.sdk.manager.CallbackData;
import com.sleepace.sdk.interfs.IResultCallback;
import com.sleepace.sdk.wificonfig.WiFiConfigHelper;


public class SleepboardBleTest {
    private final Context context;
    private final WiFiConfigHelper wifiConfigHelper;
    private boolean configuring = false;

    public SleepboardBleTest(Context context) {
        this.context = context;
        // 1. 初始化 WiFiConfigHelper
        this.wifiConfigHelper = WiFiConfigHelper.getInstance(context);
    }



    // 配网回调
    private final IResultCallback wifiConfigCallback = new IResultCallback() {
        @Override
        public void onResultCallback(CallbackData cd) {
            if (cd.isSuccess()) {
                // 配网成功
                onDeviceConfigured(cd.getResult());
            } else {
                // 配网失败
                onConfigError("Config failed: " + cd.getStatus());
            }
            configuring = false;
        }
    };


    // ... 其他代码保持不变 ...

    // 添加 @SuppressLint 注解
    @SuppressLint("MissingPermission")
    private short getDeviceType(String name) {
        if (name != null && name.startsWith("BM")) {
            return 27; // 示例设备类型,实际要根据文档定义
        }
        return -1;
    }

    // ... 其他代码保持不变 ...


    // 连接并配置设备
    @SuppressLint("MissingPermission")
    public void configureDevice(BluetoothDevice device, String serverIP, int serverPort,
                                byte[] ssidRaw, String wifiPassword) {
        if (configuring) {
            onConfigError("Configuration in progress");
            return;
        }

        configuring = true;

        // 2. 调用 BLE 配网
        wifiConfigHelper.bleWiFiConfig(
                getDeviceType(device.getName()),   // 根据设备名判断类型
                device.getAddress(),              // 设备蓝牙地址
                serverIP,                         // 要连接的服务器 IP
                serverPort,                       // 服务器端口
                ssidRaw,                         // WiFi SSID 字节数组(支持中文)
                wifiPassword,                     // WiFi 密码
                wifiConfigCallback               // 配网结果回调
        );
    }

    // 根据设备名获取设备类型
    // 配网成功回调
    protected void onDeviceConfigured(Object deviceInfo) {
        // 具体实现由调用者重写
    }

    // 错误回调
    protected void onConfigError(String error) {
        // 具体实现由调用者重写
    }

    // 释放资源
    public void release() {
        // 清理资源
        configuring = false;
    }
}