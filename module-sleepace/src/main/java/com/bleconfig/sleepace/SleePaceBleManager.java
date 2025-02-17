package com.bleconfig.sleepace;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sleepace.sdk.domain.BleDevice;
import com.sleepace.sdk.interfs.IResultCallback;
import com.sleepace.sdk.manager.CallbackData;
import com.sleepace.sdk.wificonfig.WiFiConfigHelper;
import com.sleepace.sdk.constant.StatusCode;

/**
 * B厂设备BLE配网管理类
 * 保持与原厂 WiFiConfigHelper 实现一致
 */
public class SleePaceBleManager {
	private static final String TAG = SleePaceBleManager.class.getSimpleName();

	private final Context context;
	private final WiFiConfigHelper wifiConfigHelper;
	private static volatile SleePaceBleManager instance;

	// 单例模式
	private SleePaceBleManager(Context context) {
		this.context = context.getApplicationContext();
		this.wifiConfigHelper = WiFiConfigHelper.getInstance(context);
	}

	public static SleePaceBleManager getInstance(Context context) {
		if (instance == null) {
			synchronized (SleePaceBleManager.class) {
				if (instance == null) {
					instance = new SleePaceBleManager(context);
				}
			}
		}
		return instance;
	}

	/**
	 * 开始扫描设备
	 * 直接启动原厂的扫描界面
	 */
	public void startScan(IResultCallback<Object> callback) {
		Intent intent = new Intent(context, SearchBleDeviceActivity.class);
		if(!(context instanceof android.app.Activity)) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		}
		context.startActivity(intent);
	}

	/**
	 * 开始配网
	 * 完全复用原厂的实现
	 */
	public void startConfig(BleDevice device, String serverIP, int serverPort,
							byte[] ssidRaw, String password, IResultCallback<Object> callback) {
		if (device == null || device.getDeviceType() == null) {
			Log.e(TAG, "device is null or device type is null");
			if (callback != null) {
				CallbackData<Object> cd = new CallbackData<>();
				cd.setStatus(StatusCode.PARAMETER_ERROR);
				callback.onResultCallback(cd);
			}
			return;
		}

		try {
			wifiConfigHelper.bleWiFiConfig(
					device.getDeviceType().getType(),
					device.getAddress(),
					serverIP,
					serverPort,
					ssidRaw,
					password,
					callback  // 使用传入的类型安全的回调
			);
		} catch (Exception e) {
			Log.e(TAG, "Config failed", e);
			if (callback != null) {
				CallbackData<Object> cd = new CallbackData<>();
				cd.setStatus(StatusCode.FAIL);
				callback.onResultCallback(cd);
			}
		}
	}

	/**
	 * 释放资源
	 */
	public void release() {
		instance = null;
	}
}