package com.bleconfig.sleepace;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sleepace.sdk.domain.BleDevice;
import com.sleepace.sdk.interfs.IResultCallback;
import com.sleepace.sdk.manager.CallbackData;
import com.sleepace.sdk.wificonfig.WiFiConfigHelper;
import com.sleepace.sdk.constant.StatusCode;

public class SleePaceBleManager {
	private static final String TAG = SleePaceBleManager.class.getSimpleName();
	public static final int REQUEST_SCAN_SLEEPACE = 200;

	private final Context context;
	private final WiFiConfigHelper wifiConfigHelper;
	private static volatile SleePaceBleManager instance;
	private IResultCallback<Object> scanCallback;  // 保存回调引用

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

	public void startScan(IResultCallback<Object> callback) {
		this.scanCallback = callback;  // 保存回调
		try {
			Intent intent = new Intent(context, SearchBleDeviceActivity.class);
			if (context instanceof Activity) {
				Activity activity = (Activity) context;
				activity.startActivityForResult(intent, REQUEST_SCAN_SLEEPACE);
			} else {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				context.startActivity(intent);
			}
		} catch (Exception e) {
			Log.e(TAG, "Start scan failed", e);
			if (callback != null) {
				CallbackData<Object> cd = new CallbackData<>();
				cd.setStatus(StatusCode.FAIL);
				callback.onResultCallback(cd);
			}
		}
	}

	public void handleActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQUEST_SCAN_SLEEPACE && scanCallback != null) {
			if (resultCode == Activity.RESULT_OK && data != null) {
				BleDevice device = (BleDevice) data.getSerializableExtra("extra_device");
				CallbackData<Object> cd = new CallbackData<>();
				cd.setStatus(StatusCode.SUCCESS);
				cd.setResult(device);
				scanCallback.onResultCallback(cd);
			} else {
				CallbackData<Object> cd = new CallbackData<>();
				cd.setStatus(StatusCode.FAIL);
				scanCallback.onResultCallback(cd);
			}
		}
	}

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
					callback
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

	public void stopScan() {
		Log.d(TAG, "SleePace stopScan called");
	}

	public void release() {
		scanCallback = null;
		instance = null;
	}
}