import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.sleepace.sdk.domain.BleDevice
import com.sleepace.sdk.interfs.IBleScanCallback
import com.sleepace.sdk.manager.DeviceType
import com.sleepace.sdk.manager.ble.BleHelper
import java.util.ArrayList
import java.util.regex.Pattern
import android.annotation.SuppressLint

class `SearchBleDevice-v4log`(private val context: Context) {
    private val TAG = "SearchBleDevice"
    private val bleHelper: BleHelper = BleHelper.getInstance(context)
    private var mAdapter: BleAdapter? = null

    init {
        mAdapter = BleAdapter()
    }

    class BleAdapter {
        private val TAG = "BleAdapter" // 定义 TAG
        private val list = ArrayList<BleDevice>()


        fun addBleDevice(bleDevice: BleDevice) {
            val exists = list.any { it.address == bleDevice.address }
            if (!exists) {
                // 打印设备信息，确认设备即将被添加到列表
                Log.d(TAG, "Adding BleDevice: " +
                        "modelName=${bleDevice.modelName}, " +
                        "address=${bleDevice.address}, " +
                        "deviceName=${bleDevice.deviceName}, " +
                        "deviceId=${bleDevice.deviceId}, " +
                        "deviceType=${bleDevice.deviceType}")

                list.add(bleDevice)

                // 添加日志，确认设备已添加到列表
                Log.d(TAG, "BleDevice added to list: ${bleDevice.deviceName}")
            } else {
                // 添加日志，确认设备已存在
                Log.d(TAG, "BleDevice already exists in list: ${bleDevice.deviceName}")
            }
        }

        fun getData(): List<BleDevice> {
            Log.d(TAG, "Retrieving device list with ${list.size} devices")
            // 打印完整的设备列表
            list.forEach { device ->
                Log.d(TAG, "Device in list: " +
                        "modelName=${device.modelName}, " +
                        "address=${device.address}, " +
                        "deviceName=${device.deviceName}, " +
                        "deviceId=${device.deviceId}, " +
                        "deviceType=${device.deviceType}")
            }
            return list
        }

        fun clearData() {
            Log.d(TAG, "Clearing device list")
            list.clear()
        }

        fun getItem(position: Int): BleDevice {
            val device = list[position]
            Log.d(TAG, "Retrieving device at position $position: " +
                    "modelName=${device.modelName}, " +
                    "address=${device.address}, " +
                    "deviceName=${device.deviceName}, " +
                    "deviceId=${device.deviceId}, " +
                    "deviceType=${device.deviceType}")
            return device
        }

        fun getCount(): Int {
            Log.d(TAG, "Device count: ${list.size}")
            return list.size
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bleHelper.isBluetoothOpen) {
            bleHelper.scanBleDevice(scanCallback)
        }
    }

    fun stopScan() {
        bleHelper.stopScan()
    }

    private val scanCallback = object : IBleScanCallback {
        override fun onStartScan() {
            Log.d(TAG, "Scan started")
            mAdapter?.clearData()
        }

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray) {
            var modelName = device.name
            if (modelName != null) {
                modelName = modelName.trim()
            }

            // 使用新的广播数据解析工具
            val deviceName = BleAdvUtil.getDeviceName(scanRecord)?.trim()
            Log.d(TAG, "onLeScan deviceName: $deviceName")

            // 打印原始设备信息
            Log.d(TAG, "Raw device info: " +
                    "name=${device.name}, " +
                    "address=${device.address}, " +
                    "rssi=$rssi, " +
                    "scanRecord=${scanRecord.joinToString(" ") { String.format("%02X", it) }}"
            )

            if (checkDeviceName(deviceName)) {
                val ble = BleDevice().apply {
                    this.modelName = modelName
                    this.address = device.address
                    this.deviceName = deviceName
                    this.deviceId = deviceName
                    this.deviceType = getDeviceTypeByName(deviceName)
                }

                // 打印解析后的设备信息
                Log.d(
                    TAG, "Parsed BleDevice: " +
                            "modelName=${ble.modelName}, " +
                            "address=${ble.address}, " +
                            "deviceName=${ble.deviceName}, " +
                            "deviceId=${ble.deviceId}, " +
                            "deviceType=${ble.deviceType}"
                )

                mAdapter?.addBleDevice(ble)
                // 添加日志，确认设备已添加到适配器
                Log.d(TAG, "BleDevice added to adapter: ${ble.deviceName}")
            }
        }


        override fun onStopScan() {
            Log.d(TAG, "Scan stopped")
        }
    }

    private fun checkDeviceName(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("[0-9a-zA-Z-]+")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkRestOnZ300(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(Z3)[0-9a-zA-Z-]{11}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkZ400TWP3(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(ZTW3)[0-9a-zA-Z]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkBG001A(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(GW001)[0-9a-zA-Z-]{8}$")
        val m1 = p1.matcher(deviceName)
        val p2 = Pattern.compile("^(BG01A)[0-9a-zA-Z-]{8}$")
        val m2 = p2.matcher(deviceName)
        return m1.matches() || m2.matches()
    }

    private fun checkBG002(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(BG02)[0-9a-zA-Z-]{9}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkSN913E(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SN91E)[0-9a-zA-Z-]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkM600(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(M6)[0-9a-zA-Z-]{11}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkM800(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(M8)[0-9a-zA-Z-]{11}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkBM8701(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(BM)[0-9a-zA-Z-]{11}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkBM8701_2(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(BM872)[0-9a-zA-Z-]{8}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkM8701W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(M871W)[0-9a-zA-Z-]{8}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkEW201W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(EW1W)[0-9a-zA-Z-]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkEW202W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(EW22W)[0-9a-zA-Z-]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkNoxSAW(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SA11)[0-9a-zA-Z-]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkFH601W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(FH61W)[0-9a-zA-Z-]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkNox2W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SN22)[0-9a-zA-Z-]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSM100(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SM100)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSM200(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SM200)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSM300(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SM300)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSDC10(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SDC10)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkM901L(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(M901L)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun getDeviceTypeByName(deviceName: String?): DeviceType? {
        return when {
            checkRestOnZ300(deviceName) -> DeviceType.DEVICE_TYPE_Z3
            checkEW201W(deviceName) -> DeviceType.DEVICE_TYPE_EW201W
            checkEW202W(deviceName) -> DeviceType.DEVICE_TYPE_EW202W
            checkNoxSAW(deviceName) -> DeviceType.DEVICE_TYPE_NOX_SAW
            checkM600(deviceName) -> DeviceType.DEVICE_TYPE_M600
            checkM800(deviceName) -> DeviceType.DEVICE_TYPE_M800
            checkBM8701_2(deviceName) -> {
                Log.d(TAG, "Device type detected: DEVICE_TYPE_BM8701_2 for device: $deviceName")
                DeviceType.DEVICE_TYPE_BM8701_2
            }

            checkM8701W(deviceName) -> DeviceType.DEVICE_TYPE_M8701W
            checkBM8701(deviceName) -> {
                Log.d(TAG, "Device type detected: DEVICE_TYPE_BM8701 for device: $deviceName")
                DeviceType.DEVICE_TYPE_BM8701
            }

            checkBG001A(deviceName) -> DeviceType.DEVICE_TYPE_BG001A
            checkBG002(deviceName) -> DeviceType.DEVICE_TYPE_BG002
            checkSN913E(deviceName) -> DeviceType.DEVICE_TYPE_SN913E
            checkFH601W(deviceName) -> DeviceType.DEVICE_TYPE_FH601W
            checkNox2W(deviceName) -> DeviceType.DEVICE_TYPE_NOX_2W
            checkZ400TWP3(deviceName) -> DeviceType.DEVICE_TYPE_Z400TWP_3
            checkSM100(deviceName) -> DeviceType.DEVICE_TYPE_SM100
            checkSM200(deviceName) -> DeviceType.DEVICE_TYPE_SM200
            checkSM300(deviceName) -> DeviceType.DEVICE_TYPE_SM300
            checkSDC10(deviceName) -> DeviceType.DEVICE_TYPE_SDC100
            checkM901L(deviceName) -> DeviceType.DEVICE_TYPE_M901L
            else -> {
                Log.d(TAG, "Device type not recognized for device: $deviceName")
                null
            }

        }
    }

        fun getAdapter(): BleAdapter? {
            return mAdapter
        }
    }
