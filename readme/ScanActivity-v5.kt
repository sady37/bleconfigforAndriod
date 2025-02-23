package com.example.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Handler;
import android.util.Log;

public class SearchBleDevice {
    private static final String TAG = "SearchBleDevice";
    private BluetoothAdapter mBluetoothAdapter;
    private Handler mHandler;
    private boolean isScanning = false;
    private SearchResponse response;

    @SuppressLint("MissingPermission")
    public void startScanBle(SearchResponse response) {
        try {
            this.response = response;
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }

            if (mHandler == null) {
                mHandler = new Handler();
            }

            if (isScanning) {
                stopScanBle();
            }

            isScanning = true;
            mBluetoothAdapter.getBluetoothLeScanner().startScan(mScanCallback);

            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopScanBle();
                }
            }, Constants.SCAN_PERIOD);

        } catch (Exception e) {
            Log.e(TAG, "Error during BLE scan start: ", e);
            if (response != null) {
                response.onError(e);
            }
            stopScanBle();
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScanBle() {
        try {
            isScanning = false;
            if (mBluetoothAdapter != null) {
                mBluetoothAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during BLE scan stop: ", e);
            if (response != null) {
                response.onError(e);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || device.getName() == null) {
                return;
            }

            if (!checkDeviceName(device.getName())) {
                return;
            }

            DeviceType deviceType = getDeviceTypeByName(device.getName());
            if (deviceType == DeviceType.UNKNOWN) {
                return;
            }

            SearchResult searchResult = new SearchResult();
            searchResult.name = device.getName();
            searchResult.address = device.getAddress();
            searchResult.rssi = result.getRssi();
            searchResult.scanRecord = result.getScanRecord().getBytes();
            searchResult.device = device;
            searchResult.deviceType = deviceType;

            if (response != null) {
                response.onSearchStarted();
                response.onDeviceFounded(searchResult);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            if (response != null) {
                response.onError(new Exception("Scan failed with error code: " + errorCode));
            }
        }
    };

    private boolean checkDeviceName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        // Check if device name starts with known prefix
        for (String prefix : Constants.DEVICE_PREFIX_LIST) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private DeviceType getDeviceTypeByName(String name) {
        if (name == null || name.isEmpty()) {
            return DeviceType.UNKNOWN;
        }

        // Check device type based on name prefix
        if (name.startsWith("ABC_")) {
            return DeviceType.TYPE_A;
        } else if (name.startsWith("XYZ_")) {
            return DeviceType.TYPE_B;
        } else if (name.startsWith("123_")) {
            return DeviceType.TYPE_C;
        }

        return DeviceType.UNKNOWN;
    }

    // Constants class
    public static class Constants {
        public static final long SCAN_PERIOD = 10000; // 10 seconds
        public static final String[] DEVICE_PREFIX_LIST = {
            "ABC_",
            "XYZ_",
            "123_"
        };
    }

    // Device type enum
    public enum DeviceType {
        UNKNOWN,
        TYPE_A,
        TYPE_B,
        TYPE_C
    }

    // Search result class
    public static class SearchResult {
        public String name;
        public String address;
        public int rssi;
        public byte[] scanRecord;
        public BluetoothDevice device;
        public DeviceType deviceType;
    }

    // Search response interface
    public interface SearchResponse {
        void onSearchStarted();
        void onDeviceFounded(SearchResult device);
        void onError(Exception e);
    }
}