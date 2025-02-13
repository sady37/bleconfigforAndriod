// File: app/src/main/java/com/bleconfig/bleconfig/manager/UnifiedBleManager.java
// 统一管理雷达设备(A厂)和睡眠监测设备(B厂)的BLE连接配网功能
//
// 功能区域:
// 1. 基础定义: 设备类型、回调接口、数据类
// 2. 构造与初始化
// 3. 设备扫描管理
// 4. 设备连接管理
// 5. WiFi配网管理
// 6. 回调类实现
// 7. 工具方法

package com.bleconfig.bleconfig.manager;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import blufi.espressif.BlufiCallback;
import blufi.espressif.BlufiClient;
import blufi.espressif.params.BlufiConfigureParams;
import blufi.espressif.params.BlufiParameter;
import blufi.espressif.response.BlufiStatusResponse;
import blufi.espressif.response.BlufiVersionResponse;
//import com.sleepace.sdk.manager.ble.BleHelper;
//import com.sleepace.sdk.manager.wificonfig.WiFiConfigHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class UnifiedBleManager {
    private static final String TAG = "UnifiedBleManager";
    private static final int SCAN_TIMEOUT = 10000; // 10s
    private static final int DEFAULT_MTU_LENGTH = 512;
    private static final String RADAR_PREFIX = "TSBLU";  // A厂设备前缀
    private static final String SLEEP_PREFIX = "BM";     // B厂设备前缀

    //region 基础定义
    public enum DeviceType {
        ALL,        // 扫描所有设备
        RADAR,      // A厂雷达设备
        SLEEPBOARD  // B厂睡眠监测设备
    }

    public interface UnifiedCallback {
        void onDeviceFound(UnifiedBleDevice device);
        void onDeviceConnected(UnifiedBleDevice device);
        void onDeviceDisconnected(UnifiedBleDevice device);
        void onWifiConfigured(UnifiedBleDevice device, boolean success);
        void onError(String error);
    }

    public static class UnifiedBleDevice {
        private final String name;
        private final String mac;
        private final int rssi;
        private final DeviceType type;
        private final BluetoothDevice device;
        private byte[] scanRecord;

        public UnifiedBleDevice(String name, String mac, int rssi, DeviceType type,
                                BluetoothDevice device, byte[] scanRecord) {
            this.name = name;
            this.mac = mac;
            this.rssi = rssi;
            this.type = type;
            this.device = device;
            this.scanRecord = scanRecord;
        }

        // Getters
        public String getName() { return name; }
        public String getMac() { return mac; }
        public int getRssi() { return rssi; }
        public DeviceType getType() { return type; }
        public BluetoothDevice getDevice() { return device; }
        public byte[] getScanRecord() { return scanRecord; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UnifiedBleDevice that = (UnifiedBleDevice) o;
            return mac.equals(that.mac);
        }

        @Override
        public int hashCode() {
            return mac.hashCode();
        }
    }
    //endregion

    //region 成员变量
    private final Context context;
    private final Handler mainHandler;
    private UnifiedCallback callback;
    private final List<UnifiedBleDevice> deviceList;

    // A厂SDK相关
    private BlufiClient radarClient;
    private RadarBlufiCallback radarBlufiCallback;
    private RadarGattCallback radarGattCallback;
    private volatile boolean isRadarConnected;
    private AtomicInteger sendSequence;
    private AtomicInteger readSequence;

    // B厂SDK相关
    private BluetoothGatt sleepboardGatt;
    private SleepboardGattCallback sleepboardCallback;
    private WiFiConfigHelper wifiConfigHelper;
    private volatile boolean isSleepboardConnected;

    // 扫描相关
    private boolean isScanning;
    private DeviceType currentScanType;
    private BleHelper bleHelper;
//endregion

    //region 构造与初始化
    public UnifiedBleManager(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.deviceList = new ArrayList<>();
        this.sendSequence = new AtomicInteger(0);
        this.readSequence = new AtomicInteger(0);

        // 初始化B厂SDK工具类
        this.bleHelper = BleHelper.getInstance(context);
        this.wifiConfigHelper = WiFiConfigHelper.getInstance(context);

        // 初始化回调
        this.radarBlufiCallback = new RadarBlufiCallback();
        this.radarGattCallback = new RadarGattCallback();
        this.sleepboardCallback = new SleepboardGattCallback();

        this.isRadarConnected = false;
        this.isSleepboardConnected = false;
        this.isScanning = false;
    }

    public void setCallback(UnifiedCallback callback) {
        this.callback = callback;
    }
    //endregion

    //region 权限检查
    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }
    //endregion

    //region 设备扫描
    public void startScan(DeviceType type) {
        if (!checkBlePermissions()) {
            notifyError("BLE permissions not granted");
            return;
        }

        if (isScanning) {
            return;
        }

        isScanning = true;
        currentScanType = type;
        deviceList.clear();

        // 根据设备类型启动对应扫描
        if (type == DeviceType.ALL || type == DeviceType.RADAR) {
            initRadarScan();
        }
        if (type == DeviceType.ALL || type == DeviceType.SLEEPBOARD) {
            initSleepboardScan();
        }

        // 超时停止扫描
        mainHandler.postDelayed(this::stopScan, SCAN_TIMEOUT);
    }

    private void initRadarScan() {
        if (radarClient != null) {
            radarClient.close();
        }
        radarClient = new BlufiClient(context, null);
        radarClient.setGattCallback(radarGattCallback);
        radarClient.setBlufiCallback(radarBlufiCallback);
        radarClient.connect();
    }

    private void initSleepboardScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner != null) {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    String deviceName = device.getName();
                    if (deviceName != null && deviceName.startsWith(SLEEP_PREFIX)) {
                        UnifiedBleDevice bleDevice = new UnifiedBleDevice(
                                deviceName,
                                device.getAddress(),
                                result.getRssi(),
                                DeviceType.SLEEPBOARD,
                                device,
                                result.getScanRecord().getBytes()
                        );
                        addDevice(bleDevice);
                    }
                }
            });
        }
    }

    public void stopScan() {
        isScanning = false;
        if (radarClient != null) {
            radarClient.close();
            radarClient = null;
        }
        bleHelper.stopScan();
        mainHandler.removeCallbacksAndMessages(null);
    }
    //endregion

    //region 设备连接
    public void connect(UnifiedBleDevice device) {
        if (!checkBlePermissions()) {
            notifyError("BLE permissions not granted");
            return;
        }

        stopScan();

        if (device.getType() == DeviceType.RADAR) {
            connectRadarDevice(device);
        } else {
            connectSleepboardDevice(device);
        }
    }

    private void connectRadarDevice(UnifiedBleDevice device) {
        if (radarClient != null) {
            radarClient.close();
        }

        radarClient = new BlufiClient(context, device.getDevice());
        radarClient.setGattCallback(radarGattCallback);
        radarClient.setBlufiCallback(radarBlufiCallback);
        radarClient.connect();
    }

    private void connectSleepboardDevice(UnifiedBleDevice device) {
        if (sleepboardGatt != null) {
            sleepboardGatt.disconnect();
            sleepboardGatt = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sleepboardGatt = device.getDevice().connectGatt(context, false,
                    sleepboardCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            sleepboardGatt = device.getDevice().connectGatt(context, false,
                    sleepboardCallback);
        }
    }

    public void disconnect(UnifiedBleDevice device) {
        if (device.getType() == DeviceType.RADAR) {
            if (radarClient != null) {
                radarClient.close();
                radarClient = null;
            }
        } else {
            if (sleepboardGatt != null) {
                sleepboardGatt.disconnect();
                sleepboardGatt = null;
            }
        }
    }
    //endregion

    //region WiFi配网
    public void configureWifi(UnifiedBleDevice device, String ssid, String password) {
        if (device.getType() == DeviceType.RADAR) {
            configureRadarWifi(ssid, password);
        } else {
            configureSleepboardWifi(device, ssid, password);
        }
    }

    private void configureRadarWifi(String ssid, String password) {
        if (!isRadarConnected || radarClient == null) {
            notifyError("Radar device not connected");
            return;
        }

        BlufiConfigureParams params = new BlufiConfigureParams();
        params.setOpMode(BlufiParameter.OP_MODE_STA);
        params.setStaSSIDBytes(ssid.getBytes());
        params.setStaPassword(password);
        radarClient.configure(params);
    }

    private void configureSleepboardWifi(UnifiedBleDevice device, String ssid, String password) {
        if (!isSleepboardConnected || sleepboardGatt == null) {
            notifyError("Sleepboard device not connected");
            return;
        }

        // 直接写入WiFi配置到特征值
        BluetoothGattCharacteristic characteristic = findWifiConfigCharacteristic();
        if (characteristic != null) {
            byte[] config = formatWifiConfig(ssid, password);
            characteristic.setValue(config);
            sleepboardGatt.writeCharacteristic(characteristic);
        }
    }

//endregion

    //region 回调实现
    private class RadarGattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String devAddr = gatt.getDevice().getAddress();

            if (status == BluetoothGatt.GATT_SUCCESS) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        isRadarConnected = true;
                        UnifiedBleDevice device = createRadarDevice(gatt.getDevice());
                        notifyDeviceConnected(device);
                        gatt.discoverServices();
                        break;

                    case BluetoothProfile.STATE_DISCONNECTED:
                        isRadarConnected = false;
                        gatt.close();
                        UnifiedBleDevice disconnectedDevice = createRadarDevice(gatt.getDevice());
                        notifyDeviceDisconnected(disconnectedDevice);
                        break;
                }
            } else {
                isRadarConnected = false;
                gatt.close();
                notifyError(String.format(Locale.US,
                        "Radar device connection failed: status=%d", status));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect();
                return;
            }

            BluetoothGattService service = gatt.getService(BlufiParameter.UUID_SERVICE);
            if (service == null) {
                gatt.disconnect();
                return;
            }

            BluetoothGattCharacteristic writeChar =
                    service.getCharacteristic(BlufiParameter.UUID_WRITE_CHARACTERISTIC);
            BluetoothGattCharacteristic notifyChar =
                    service.getCharacteristic(BlufiParameter.UUID_NOTIFICATION_CHARACTERISTIC);

            if (writeChar == null || notifyChar == null) {
                gatt.disconnect();
                return;
            }

            gatt.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(
                    BlufiParameter.UUID_NOTIFICATION_DESCRIPTOR);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }
    }

    private class RadarBlufiCallback extends BlufiCallback {
        @Override
        public void onGattPrepared(BlufiClient client, BluetoothGatt gatt,
                                   BluetoothGattService service,
                                   BluetoothGattCharacteristic writeChar,
                                   BluetoothGattCharacteristic notifyChar) {
            if (service == null || writeChar == null || notifyChar == null) {
                gatt.disconnect();
                return;
            }

            int mtu = DEFAULT_MTU_LENGTH;
            boolean requestMtu = gatt.requestMtu(mtu);
            if (!requestMtu) {
                notifyError("Failed to request MTU");
            }
        }

        @Override
        public void onPostConfigureParams(BlufiClient client, int status) {
            UnifiedBleDevice currentDevice = deviceList.stream()
                    .filter(d -> d.getType() == DeviceType.RADAR)
                    .findFirst()
                    .orElse(null);

            if (status == STATUS_SUCCESS) {
                if (currentDevice != null) {
                    notifyWifiConfigured(currentDevice, true);
                } else {
                    notifyError("Connected device not found");
                }
            } else {
                notifyError("WiFi configuration failed");
            }
        }
    }

    private class SleepboardGattCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (!checkBluetoothPermission()) {
                return;
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        isSleepboardConnected = true;
                        UnifiedBleDevice device = createSleepboardDevice(gatt.getDevice());
                        notifyDeviceConnected(device);
                        try {
                            gatt.discoverServices();
                        } catch (SecurityException e) {
                            notifyError("Failed to discover services: " + e.getMessage());
                        }
                        break;

                    case BluetoothProfile.STATE_DISCONNECTED:
                        isSleepboardConnected = false;
                        try {
                            gatt.close();
                        } catch (SecurityException e) {
                            notifyError("Failed to close GATT: " + e.getMessage());
                        }
                        UnifiedBleDevice disconnectedDevice = createSleepboardDevice(gatt.getDevice());
                        notifyDeviceDisconnected(disconnectedDevice);
                        break;
                }
            } else {
                isSleepboardConnected = false;
                try {
                    gatt.close();
                } catch (SecurityException e) {
                    notifyError("Failed to close GATT: " + e.getMessage());
                }
                notifyError(String.format(Locale.US,
                        "Sleepboard device connection failed: status=%d", status));
            }
        }

        private boolean checkBluetoothPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    notifyError("Bluetooth connect permission not granted");
                    return false;
                }
            }
            return true;
        }
    }
    //endregion

    //region 工具方法
    private void addDevice(UnifiedBleDevice device) {
        if (!deviceList.contains(device)) {
            deviceList.add(device);
            notifyDeviceFound(device);
        }
    }

    private UnifiedBleDevice createRadarDevice(BluetoothDevice device) {
        return new UnifiedBleDevice(
                device.getName(),
                device.getAddress(),
                0,
                DeviceType.RADAR,
                device,
                null
        );
    }

    private UnifiedBleDevice createSleepboardDevice(BluetoothDevice device) {
        return new UnifiedBleDevice(
                device.getName(),
                device.getAddress(),
                0,
                DeviceType.SLEEPBOARD,
                device,
                null
        );
    }

    private void notifyDeviceFound(UnifiedBleDevice device) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDeviceFound(device);
            }
        });
    }

    private void notifyDeviceConnected(UnifiedBleDevice device) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDeviceConnected(device);
            }
        });
    }

    private void notifyDeviceDisconnected(UnifiedBleDevice device) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDeviceDisconnected(device);
            }
        });
    }

    private void notifyWifiConfigured(UnifiedBleDevice device, boolean success) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onWifiConfigured(device, success);
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onError(error);
            }
        });
    }

    private boolean isDeviceTypeA(String name) {
        return name != null && name.startsWith(RADAR_PREFIX);
    }

    private boolean isDeviceTypeB(String name) {
        return name != null && name.startsWith(SLEEP_PREFIX);
    }
    //endregion
}
