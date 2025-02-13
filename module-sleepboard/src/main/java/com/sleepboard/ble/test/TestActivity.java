// 文件路径: module-sleepboard/src/main/java/com/sleepboard/ble/test/TestActivity.java

package com.sleepboard.ble.test;

import android.bluetooth.BluetoothDevice;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import java.lang.reflect.Method;  // 添加这行导入

public class TestActivity extends AppCompatActivity {
    private static final String TAG = "SleepboardTest";
    private SleepboardBleTest bleTest;
    private WifiManager wifiManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        // 创建测试实例
        bleTest = new SleepboardBleTest(this) {
            @Override
            protected void onDeviceConfigured(Object deviceInfo) {
                Log.d(TAG, "Device configured: " + deviceInfo);
            }

            @Override
            protected void onConfigError(String error) {
                Log.e(TAG, "Config error: " + error);
            }
        };
    }

    // 测试配网功能
    private void testConfig(BluetoothDevice device) {
        bleTest.configureDevice(
                device,
                "192.168.1.100",  // 服务器 IP
                29020,            // 服务器端口
                getSsidBytes(),   // 从 WifiInfo 获取 SSID 字节数组
                "wifi_password"   // WiFi 密码
        );
    }

    // 获取 SSID 字节数组(支持中文)
    private byte[] getSsidBytes() {
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo != null) {
            try {
                Method method = wifiInfo.getClass().getMethod("getWifiSsid");
                method.setAccessible(true);
                Object wifiSsid = method.invoke(wifiInfo);
                method = wifiSsid.getClass().getMethod("getOctets");
                method.setAccessible(true);
                return (byte[]) method.invoke(wifiSsid);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleTest != null) {
            bleTest.release();
        }
    }
}