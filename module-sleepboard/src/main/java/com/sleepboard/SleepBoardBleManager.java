package com.sleepboard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.sleepace.sdk.wificonfig.WiFiConfigHelper;
import com.sleepace.sdk.interfs.IResultCallback;
import com.sleepace.sdk.manager.CallbackData;
import com.sleepace.sdk.constant.StatusCode;
import com.sleepace.sdk.domain.BleDevice;
import com.sleepace.sdk.util.SdkLog;

/**
 * B厂设备BLE配网管理类
 */
public class SleepBoardBleManager {
	private static final String TAG = "SleepBoardBleManager";
	private static final short DEVICE_TYPE = 27; // B厂设备类型ID

	private final WiFiConfigHelper wifiConfigHelper;
	private static SleepBoardBleManager instance;

	/**
	 * 获取单例实例
	 */
	public static synchronized SleepBoardBleManager getInstance(Context context) {
		if (instance == null) {
			instance = new SleepBoardBleManager(context);
		}
		return instance;
	}

	private SleepBoardBleManager(Context context) {
		wifiConfigHelper = WiFiConfigHelper.getInstance(context);
	}

	/**
	 * 开始配网
	 * @param btAddress 蓝牙设备地址
	 * @param serverIP 服务器IP地址
	 * @param serverPort 服务器端口
	 * @param ssid WiFi SSID
	 * @param password WiFi 密码
	 * @param callback 配网结果回调
	 */
	public void startConfig(String btAddress, String serverIP, int serverPort, String ssid, String password,
							final IResultCallback callback) {

		// 参数检查
		if (btAddress == null || btAddress.isEmpty()) {
			Log.e(TAG, "BT address cannot be empty");
			if (callback != null) {
				CallbackData callbackData = new CallbackData();
				callbackData.setStatus(StatusCode.PARAMETER_ERROR);
				callback.onResultCallback(callbackData);
			}
			return;
		}

		if (serverIP == null || serverIP.isEmpty()) {
			Log.e(TAG, "Server IP cannot be empty");
			if (callback != null) {
				CallbackData callbackData = new CallbackData();
				callbackData.setStatus(StatusCode.PARAMETER_ERROR);
				callback.onResultCallback(callbackData);
			}
			return;
		}

		if (serverPort <= 0 || serverPort > 65535) {
			Log.e(TAG, "Invalid server port: " + serverPort);
			if (callback != null) {
				CallbackData callbackData = new CallbackData();
				callbackData.setStatus(StatusCode.PARAMETER_ERROR);
				callback.onResultCallback(callbackData);
			}
			return;
		}

		if (ssid == null || ssid.isEmpty()) {
			Log.e(TAG, "SSID cannot be empty");
			if (callback != null) {
				CallbackData callbackData = new CallbackData();
				callbackData.setStatus(StatusCode.PARAMETER_ERROR);
				callback.onResultCallback(callbackData);
			}
			return;
		}

		// 启动配网
		try {
			Log.d(TAG, "Starting config for device: " + btAddress);
			wifiConfigHelper.bleWiFiConfig(
					DEVICE_TYPE,
					btAddress,
					serverIP,
					serverPort,
					ssid.getBytes(),
					password,
					callback
			);
		} catch (Exception e) {
			Log.e(TAG, "Config failed", e);
			if (callback != null) {
				CallbackData callbackData = new CallbackData();
				callbackData.setStatus(StatusCode.FAIL);
				callback.onResultCallback(callbackData);
			}
		}
	}

	/**
	 * 获取 SDK 版本信息
	 * @param context 上下文
	 * @return SDK 版本号
	 */
	public String getSDKVersion(Context context) {
		try {
			return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
		} catch (PackageManager.NameNotFoundException e) {
			e.printStackTrace();
			return "Unknown";
		}
	}

	/**
	 * 释放资源
	 */
	public void release() {
		instance = null;
	}
}