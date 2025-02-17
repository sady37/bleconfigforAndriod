package com.bleconfig.sleepace;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sleepace.sdk.domain.BleDevice;
import com.sleepace.sdk.interfs.IBleScanCallback;
import com.sleepace.sdk.manager.DeviceType;
import com.sleepace.sdk.manager.ble.BleHelper;
import com.sleepace.sdk.util.SdkLog;

/**
 * B厂设备扫描类
 * 功能：
 * 1. BLE设备扫描
 * 2. 设备名称校验
 * 3. 设备类型识别
 */
public class SearchBleDeviceActivity extends Activity {
	private static final String TAG = SearchBleDeviceActivity.class.getSimpleName();
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

	private IBleScanCallback scanCallback = new IBleScanCallback() {
		@Override
		public void onStartScan() {
			Log.d(TAG, "Start scanning...");
		}

		@SuppressLint("MissingPermission")
		@Override
		public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
			String modelName = device.getName();
			if(modelName != null){
				modelName = modelName.trim();
			}
			String deviceName = BleDeviceNameUtil.getBleDeviceName(0xff, scanRecord);
			if(deviceName != null){
				deviceName = deviceName.trim();
			}
			SdkLog.log(TAG+" onLeScan deviceName:" + deviceName);
			if(checkDeviceName(deviceName)){
				BleDevice ble = new BleDevice();
				ble.setModelName(modelName);
				ble.setAddress(device.getAddress());
				ble.setDeviceName(deviceName);
				ble.setDeviceId(deviceName);
				ble.setDeviceType(getDeviceTypeByName(deviceName));

				Intent data = new Intent();
				data.putExtra(EXTRA_DEVICE, ble);
				setResult(RESULT_OK, data);
				finish();
			}
		}

		@Override
		public void onStopScan() {
			Log.d(TAG, "Scan stopped");
		}
	};

	/**
	 * 根据设备名称获取设备类型
	 */
	static DeviceType getDeviceTypeByName(String deviceName) {
		if(checkRestOnZ300(deviceName)) {
			return DeviceType.DEVICE_TYPE_Z3;
		}else if(checkEW201W(deviceName)) {
			return DeviceType.DEVICE_TYPE_EW201W;
		}else if(checkEW202W(deviceName)) {
			return DeviceType.DEVICE_TYPE_EW202W;
		}else if(checkNoxSAW(deviceName)) {
			return DeviceType.DEVICE_TYPE_NOX_SAW;
		}else if(checkM600(deviceName)) {
			return DeviceType.DEVICE_TYPE_M600;
		}else if(checkM800(deviceName)) {
			return DeviceType.DEVICE_TYPE_M800;
		}else if(checkBM8701_2(deviceName)) {
			return DeviceType.DEVICE_TYPE_BM8701_2;
		}else if(checkM8701W(deviceName)) {
			return DeviceType.DEVICE_TYPE_M8701W;
		}else if(checkBM8701(deviceName)) {
			// 我们当前集成的设备类型
			return DeviceType.DEVICE_TYPE_BM8701;
		}else if(checkBG001A(deviceName)) {
			return DeviceType.DEVICE_TYPE_BG001A;
		}else if(checkBG002(deviceName)) {
			return DeviceType.DEVICE_TYPE_BG002;
		}else if(checkSN913E(deviceName)) {
			return DeviceType.DEVICE_TYPE_SN913E;
		}else if(checkFH601W(deviceName)) {
			return DeviceType.DEVICE_TYPE_FH601W;
		}else if(checkNox2W(deviceName)) {
			return DeviceType.DEVICE_TYPE_NOX_2W;
		}else if(checkZ400TWP3(deviceName)) {
			return DeviceType.DEVICE_TYPE_Z400TWP_3;
		}else if(checkSM100(deviceName)) {
			return DeviceType.DEVICE_TYPE_SM100;
		}else if(checkSM200(deviceName)) {
			return DeviceType.DEVICE_TYPE_SM200;
		}else if(checkSM300(deviceName)) {
			return DeviceType.DEVICE_TYPE_SM300;
		}else if(checkSDC10(deviceName)) {
			return DeviceType.DEVICE_TYPE_SDC100;
		}else if(checkM901L(deviceName)) {
			return DeviceType.DEVICE_TYPE_M901L;
		}
		return null;
	}

	/**
	 * 检查设备名称格式
	 */
	static boolean checkDeviceName(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("[0-9a-zA-Z-]+");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkRestOnZ300(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(Z3)[0-9a-zA-Z-]{11}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkZ400TWP3(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(ZTW3)[0-9a-zA-Z]{9}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkBG001A(String deviceName) {
		if (deviceName == null) return false;
		Pattern p1 = Pattern.compile("^(GW001)[0-9a-zA-Z-]{8}$");
		Matcher m1 = p1.matcher(deviceName);
		Pattern p2 = Pattern.compile("^(BG01A)[0-9a-zA-Z-]{8}$");
		Matcher m2 = p2.matcher(deviceName);
		return m1.matches() || m2.matches();
	}

	static boolean checkBG002(String deviceName) {
		if (deviceName == null) return false;
		Pattern p1 = Pattern.compile("^(BG02)[0-9a-zA-Z-]{9}$");
		Matcher m1 = p1.matcher(deviceName);
		return m1.matches();
	}

	static boolean checkSN913E(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SN91E)[0-9a-zA-Z-]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkM600(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(M6)[0-9a-zA-Z-]{11}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkM800(String deviceName) {
		if (deviceName == null) return false;
		Pattern p1 = Pattern.compile("^(M8)[0-9a-zA-Z-]{11}$");
		Matcher m1 = p1.matcher(deviceName);
		return m1.matches();
	}

	/**
	 * BM8701 设备名称规则检查
	 * 规则：以"BM"开头，后跟11个字符（数字、字母或横杠）
	 */
	static boolean checkBM8701(String deviceName) {
		if (deviceName == null) return false;
		Pattern p1 = Pattern.compile("^(BM)[0-9a-zA-Z-]{11}$");
		Matcher m1 = p1.matcher(deviceName);
		return m1.matches();
	}

	static boolean checkBM8701_2(String deviceName) {
		if (deviceName == null) return false;
		Pattern p1 = Pattern.compile("^(BM872)[0-9a-zA-Z-]{8}$");
		Matcher m1 = p1.matcher(deviceName);
		return m1.matches();
	}

	static boolean checkM8701W(String deviceName) {
		if (deviceName == null) return false;
		Pattern p1 = Pattern.compile("^(M871W)[0-9a-zA-Z-]{8}$");
		Matcher m1 = p1.matcher(deviceName);
		return m1.matches();
	}

	static boolean checkEW201W(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(EW1W)[0-9a-zA-Z-]{9}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkEW202W(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(EW22W)[0-9a-zA-Z-]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkNoxSAW(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SA11)[0-9a-zA-Z-]{9}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkFH601W(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(FH61W)[0-9a-zA-Z-]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkNox2W(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SN22)[0-9a-zA-Z-]{9}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkSM100(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SM100)[0-9a-zA-Z]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkSM200(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SM200)[0-9a-zA-Z]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkSM300(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SM300)[0-9a-zA-Z]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkSDC10(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(SDC10)[0-9a-zA-Z]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	static boolean checkM901L(String deviceName) {
		if (deviceName == null) return false;
		Pattern p = Pattern.compile("^(M901L)[0-9a-zA-Z]{8}$");
		Matcher m = p.matcher(deviceName);
		return m.matches();
	}

	@SuppressLint("MissingPermission")
	@Override
	protected void onDestroy() {
		super.onDestroy();
		bleHelper.stopScan();
	}
}