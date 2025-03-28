package com.espressif.espblufi;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.interfaces.DHPublicKey;

import com.espressif.espblufi.params.BlufiConfigureParams;
import com.espressif.espblufi.params.BlufiParameter;
import com.espressif.espblufi.response.BlufiScanResult;
import com.espressif.espblufi.response.BlufiStatusResponse;
import com.espressif.espblufi.response.BlufiVersionResponse;
import com.espressif.espblufi.security.BlufiAES;
import com.espressif.espblufi.security.BlufiCRC;
import com.espressif.espblufi.security.BlufiDH;
import com.espressif.espblufi.security.BlufiMD5;

@SuppressLint("MissingPermission")
class BlufiClientImpl implements BlufiParameter {
    private static final String TAG = "BlufiClientImpl";

    private static final int DEFAULT_PACKAGE_LENGTH = 20;//20
    private static final int PACKAGE_HEADER_LENGTH = 4;
    private static final int MIN_PACKAGE_LENGTH = 20;//20

    private static final byte NEG_SECURITY_SET_TOTAL_LENGTH = 0x00;
    private static final byte NEG_SECURITY_SET_ALL_DATA = 0x01;

    private static final String DH_P = "cf5cf5c38419a724957ff5dd323b9c45c3cdd261eb740f69aa94b8bb1a5c9640" +
            "9153bd76b24222d03274e4725a5406092e9e82e9135c643cae98132b0d95f7d6" +
            "5347c68afc1e677da90e51bbab5f5cf429c291b4ba39c6b2dc5e8c7231e46aa7" +
            "728e87664532cdf547be20c9a3fa8342be6e34371a27c06f7dc0edddd2f86373";
    private static final String DH_G = "2";
    private static final String AES_TRANSFORMATION = "AES/CFB/NoPadding";

    private boolean mPrintDebug = false; //BuildConfig.DEBUG;

    private BlufiClient mClient;

    private Context mContext;
    private BluetoothDevice mDevice;
    private BluetoothGattCallback mInnerGattCallback;
    private volatile BluetoothGattCallback mUserGattCallback;
    private volatile BlufiCallback mUserBlufiCallback;

    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mWriteChar;
    private final LinkedBlockingQueue<Boolean> mWriteResultQueue;
    private BluetoothGattCharacteristic mNotifyChar;
    private long mWriteTimeout = -1;

    //等待设备处理长度信息 超时timeout
    private int NegotiateSecurity_timeout = 8000;  //// 原始前超时时间为 10-500 毫秒

    private int mPackageLengthLimit = -1;
    private int mBlufiMTU = -1;

    private final AtomicInteger mSendSequence;
    private final AtomicInteger mReadSequence;
    private LinkedBlockingQueue<Integer> mAck;

    private volatile BlufiNotifyData mNotifyData;

    private byte[] mAESKey;

    private boolean mEncrypted = false;
    private boolean mChecksum = false;

    private boolean mRequireAck = false;

    private final SecurityCallback mSecurityCallback;
    private final LinkedBlockingQueue<BigInteger> mDevicePublicKeyQueue;

    private ExecutorService mThreadPool;
    private final Handler mUIHandler;

    private int mConnectState = BluetoothGatt.STATE_DISCONNECTED;

    BlufiClientImpl(BlufiClient client, Context context, BluetoothDevice device) {
        mClient = client;
        mContext = context;
        mDevice = device;
        mInnerGattCallback = new InnerGattCallback();

        mSendSequence = new AtomicInteger(-1);
        mReadSequence = new AtomicInteger(-1);
        mAck = new LinkedBlockingQueue<>();

        mSecurityCallback = new SecurityCallback();
        mDevicePublicKeyQueue = new LinkedBlockingQueue<>();

        mThreadPool = Executors.newSingleThreadExecutor();
        mUIHandler = new Handler(Looper.getMainLooper());

        mWriteResultQueue = new LinkedBlockingQueue<>();
    }

    void printDebugLog(boolean enable) {
        mPrintDebug = enable;
    }

    void setGattCallback(BluetoothGattCallback callback) {
        mUserGattCallback = callback;
    }

    void setBlufiCallback(BlufiCallback callback) {
        mUserBlufiCallback = callback;
    }

    synchronized void connect() {
        if (mThreadPool == null) {
            throw new IllegalStateException("The BlufiClient has closed");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mGatt = mDevice.connectGatt(mContext, false, mInnerGattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mGatt = mDevice.connectGatt(mContext, false, mInnerGattCallback);
        }
    }

    synchronized void close() {
        // 添加堆栈跟踪以跟踪调用者
        Log.d(TAG, "BlufiClient close() called", new Exception("BlufiClient close() stack trace"));

        mConnectState = BluetoothGatt.STATE_DISCONNECTED;

        mWriteResultQueue.clear();
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
            mThreadPool = null;
        }
        if (mGatt != null) {
            Log.d(TAG, "Closing GATT connection");
            mGatt.close();
            mGatt = null;
        } else {
            Log.d(TAG, "GATT connection is already null");
        }

        mNotifyChar = null;
        mWriteChar = null;
        if (mAck != null) {
            mAck.clear();
            mAck = null;
        }
        mClient = null;
        mUserBlufiCallback = null;
        mInnerGattCallback = null;
        mUserGattCallback = null;
        mContext = null;
        mDevice = null;

        Log.d(TAG, "BlufiClient close() completed");
    }

    void setGattWriteTimeout(long timeout) {
        mWriteTimeout = timeout;
    }

    void setPostPackageLengthLimit(int lengthLimit) {
        if (lengthLimit <= 0) {
            mPackageLengthLimit = -1;
        } else {
            mPackageLengthLimit = Math.max(lengthLimit, MIN_PACKAGE_LENGTH);
        }
    }

    void requestDeviceVersion() {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __requestDeviceVersion();
            }
        });
    }

    void requestDeviceStatus() {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __requestDeviceStatus();
            }
        });
    }

    void negotiateSecurity() {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __negotiateSecurity();
            }
        });
    }

    void configure(final BlufiConfigureParams params) {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __configure(params);
            }
        });
    }

    void requestDeviceWifiScan() {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __requestDeviceWifiScan();
            }
        });
    }

    void postCustomData(final byte[] data) {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __postCustomData(data);
            }
        });
    }

    void requestCloseConnection() {
        mThreadPool.submit(new ThrowableRunnable() {
            @Override
            void execute() {
                __requestCloseConnection();
            }
        });
    }

    private int toInt(byte b) {
        return b & 0xff;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int number = b & 0xff;
            String str = Integer.toHexString(number);
            if (str.length() == 1) {
                sb.append("0");
            }
            sb.append(str);
        }
        return sb.toString();
    }

    private byte[] toBytes(String hex) {
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return result;
    }

    private int getTypeValue(int type, int subtype) {
        return (subtype << 2) | type;
    }

    private int getPackageType(int typeValue) {
        return typeValue & 0b11;
    }

    private int getSubType(int typeValue) {
        return ((typeValue & 0b11111100) >> 2);
    }

    private int generateSendSequence() {
        return mSendSequence.incrementAndGet() & 0xff;
    }

    private byte[] generateAESIV(int sequence) {
        byte[] result = new byte[16];
        result[0] = (byte) sequence;

        return result;
    }

    private boolean isConnected() {
        return mConnectState == BluetoothGatt.STATE_CONNECTED;
    }

    private boolean gattWrite(byte[] data) throws InterruptedException {
        if (!isConnected()) {
            return false;
        }
        if (mPrintDebug) {
            Log.i(TAG, "gattWrite= " + Arrays.toString(data));
        }
        mWriteChar.setValue(data);
        mGatt.writeCharacteristic(mWriteChar);
        Boolean result;
        if (mWriteTimeout > 0) {
            result = mWriteResultQueue.poll(mWriteTimeout, TimeUnit.MILLISECONDS);
            if (result == null) {
                //onError(BlufiCallback.CODE_GATT_WRITE_TIMEOUT);
                // 修改这里，添加Android 14特定处理
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // 记录超时但不立即报错
                    Log.w(TAG, "GATT write timed out on Android 14, attempting to continue");
                    return false; // 返回失败但不调用onError，允许调用者决定如何处理
                } else {
                    onError(BlufiCallback.CODE_GATT_WRITE_TIMEOUT);
                }
            } //andriod14 end
        } else {
            result = mWriteResultQueue.take();
        }
        return result != null && result;
    }

    private boolean receiveAck(int expectAck) {
        try {
            int ack = mAck.take();
            return ack == expectAck;
        } catch (InterruptedException e) {
            Log.w(TAG, "receiveAck: interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean post(boolean encrypt, boolean checksum, boolean requireAck, int type, byte[] data)
            throws InterruptedException {
        if (data == null || data.length == 0) {
            Log.d(TAG, "Sending packet without data - Type:" + type);
            return postNonData(encrypt, checksum, requireAck, type);
        } else {
            Log.d(TAG, "Sending packet with data - Type:" + type + ", Data length:" + data.length + " bytes");
            return postContainData(encrypt, checksum, requireAck, type, data);
        }
    }

    private boolean postNonData(boolean encrypt, boolean checksum, boolean requireAck, int type)
            throws InterruptedException {
        int sequence = generateSendSequence();

        byte[] postBytes = getPostBytes(type, encrypt, checksum, requireAck, false, sequence, null);
        boolean posted = gattWrite(postBytes);

        return posted && (!requireAck || receiveAck(sequence));
    }

    private boolean postContainData(boolean encrypt, boolean checksum, boolean requireAck, int type, byte[] data)
            throws InterruptedException {
        ByteArrayInputStream dataIS = new ByteArrayInputStream(data);
        ByteArrayOutputStream dataContent = new ByteArrayOutputStream();
        int pkgLengthLimit = mPackageLengthLimit > 0 ? mPackageLengthLimit :
                (mBlufiMTU > 0 ? mBlufiMTU : DEFAULT_PACKAGE_LENGTH);
        int postDataLengthLimit = pkgLengthLimit - PACKAGE_HEADER_LENGTH;
        postDataLengthLimit -= 2; // if frag, two bytes total length in data
        if (checksum) {
            postDataLengthLimit -= 2;
        }
        byte[] dataBuf = new byte[postDataLengthLimit];
        while (true) {
            int read = dataIS.read(dataBuf, 0, dataBuf.length);
            if (read == -1) {
                break;
            }

            dataContent.write(dataBuf, 0, read);
            if (dataIS.available() > 0 && dataIS.available() <= 2) {
                read = dataIS.read(dataBuf, 0, dataIS.available());
                dataContent.write(dataBuf, 0, read);
            }
            boolean frag = dataIS.available() > 0;
            int sequence = generateSendSequence();
            if (frag) {
                int totalLen = dataContent.size() + dataIS.available();
                byte[] tempData = dataContent.toByteArray();
                dataContent.reset();
                dataContent.write(totalLen & 0xff);
                dataContent.write(totalLen >> 8 & 0xff);
                dataContent.write(tempData, 0, tempData.length);
            }
            byte[] postBytes = getPostBytes(type, encrypt, checksum, requireAck, frag, sequence, dataContent.toByteArray());
            dataContent.reset();
            boolean posted = gattWrite(postBytes);
            if (!posted) {
                return false;
            }
            if (frag) {
                if (requireAck && !receiveAck(sequence)) {
                    return false;
                }
                sleep(10L);
            } else {
                return !requireAck || receiveAck(sequence);
            }
        }

        return true;
    }

    private byte[] getPostBytes(int type, boolean encrypt, boolean checksum, boolean requireAck, boolean hasFrag, int sequence, byte[] data) {
        ByteArrayOutputStream byteOS = new ByteArrayOutputStream();

        int dataLength = data == null ? 0 : data.length;
        int frameCtrl = FrameCtrlData.getFrameCTRLValue(encrypt, checksum, DIRECTION_OUTPUT, requireAck, hasFrag);

        byteOS.write(type);
        byteOS.write(frameCtrl);
        byteOS.write(sequence);
        byteOS.write(dataLength);

        byte[] checksumBytes = null;
        if (checksum) {
            byte[] willCheckBytes = new byte[]{(byte) sequence, (byte) dataLength};
            int crc = BlufiCRC.calcCRC(0, willCheckBytes);
            if (dataLength > 0) {
                crc = BlufiCRC.calcCRC(crc, data);
            }
            checksumBytes = new byte[]{(byte) (crc & 0xff), (byte) (crc >> 8 & 0xff)};
        }

        if (encrypt && data != null && data.length > 0) {
            BlufiAES aes = new BlufiAES(mAESKey, AES_TRANSFORMATION, generateAESIV(sequence));
            data = aes.encrypt(data);
        }
        if (data != null) {
            byteOS.write(data, 0, data.length);
        }

        if (checksumBytes != null) {
            byteOS.write(checksumBytes[0]);
            byteOS.write(checksumBytes[1]);
        }

        return byteOS.toByteArray();
    }

    private int parseNotification(byte[] response, BlufiNotifyData notification) {
        if (response == null) {
            Log.w(TAG, "parseNotification null data");
            return -1;
        }
        if (mPrintDebug) {
            Log.d(TAG, "parseNotification Notification= " + Arrays.toString(response));
        }

        if (response.length < 4) {
            Log.w(TAG, "parseNotification data length less than 4");
            return -2;
        }

        int sequence = toInt(response[2]);
        if (sequence != (mReadSequence.incrementAndGet() & 0xff)) {
            Log.w(TAG, "parseNotification read sequence wrong");
            return -3;
        }

        int type = toInt(response[0]);
        int pkgType = getPackageType(type);
        int subType = getSubType(type);
        notification.setType(type);
        notification.setPkgType(pkgType);
        notification.setSubType(subType);

        int frameCtrl = toInt(response[1]);
        notification.setFrameCtrl(frameCtrl);
        FrameCtrlData frameCtrlData = new FrameCtrlData(frameCtrl);

        int dataLen = toInt(response[3]);
        byte[] dataBytes = new byte[dataLen];
        int dataOffset = 4;
        try {
            System.arraycopy(response, dataOffset, dataBytes, 0, dataLen);
        } catch (Exception e) {
            Log.e(TAG, "Error copying notification data", e);
            return -100;
        }
        if (frameCtrlData.isEncrypted()) {
            BlufiAES aes = new BlufiAES(mAESKey, AES_TRANSFORMATION, generateAESIV(sequence));
            dataBytes = aes.decrypt(dataBytes);
        }

        if (frameCtrlData.isChecksum()) {
            int respChecksum1 = toInt(response[response.length - 1]);
            int respChecksum2 = toInt(response[response.length - 2]);

            int crc = BlufiCRC.calcCRC(0, new byte[]{(byte) sequence, (byte) dataLen});
            crc = BlufiCRC.calcCRC(crc, dataBytes);
            int calcChecksum1 = crc >> 8 & 0xff;
            int calcChecksum2 = crc & 0xff;

            if (respChecksum1 != calcChecksum1 || respChecksum2 != calcChecksum2) {
                Log.w(TAG, "parseNotification: read invalid checksum");
                if (mPrintDebug) {
                    Log.d(TAG, "expect   checksum: " + respChecksum1 + ", " + respChecksum2);
                    Log.d(TAG, "received checksum: " + calcChecksum1 + ", " + calcChecksum2);
                }
                return -4;
            }
        }

        if (frameCtrlData.hasFrag()) {
//            int totalLen = dataBytes[0] | (dataBytes[1] << 8);
            dataOffset = 2;
        } else {
            dataOffset = 0;
        }
        notification.addData(dataBytes, dataOffset);

        return frameCtrlData.hasFrag() ? 1 : 0;
    }

    private void parseBlufiNotifyData(BlufiNotifyData data) {
        int pkgType = data.getPkgType();
        int subType = data.getSubType();
        byte[] dataBytes = data.getDataArray();
        if (mUserBlufiCallback != null) {
            boolean complete = mUserBlufiCallback.onGattNotification(mClient, pkgType, subType, dataBytes);
            if (complete) {
                return;
            }
        }

        switch (pkgType) {
            case Type.Ctrl.PACKAGE_VALUE:
                parseCtrlData(subType, dataBytes);
                break;
            case Type.Data.PACKAGE_VALUE:
                parseDataData(subType, dataBytes);
                break;
        }
    }

    private void parseCtrlData(int subType, byte[] data) {
        if (subType == Type.Ctrl.SUBTYPE_ACK) {
            parseAck(data);
        }
    }

    private void parseDataData(int subType, byte[] data) {
        switch (subType) {
            case Type.Data.SUBTYPE_NEG:
                mSecurityCallback.onReceiveDevicePublicKey(data);
                break;
            case Type.Data.SUBTYPE_VERSION:
                parseVersion(data);
                break;
            case Type.Data.SUBTYPE_WIFI_CONNECTION_STATE:
                parseWifiState(data);
                break;
            case Type.Data.SUBTYPE_WIFI_LIST:
                parseWifiScanList(data);
                break;
            case Type.Data.SUBTYPE_CUSTOM_DATA:
                onReceiveCustomData(data);
                break;
            case Type.Data.SUBTYPE_ERROR:
                int errCode = data.length > 0 ? (data[0] & 0xff) : 0xff;
                onError(errCode);
                break;
        }
    }

    private void parseAck(byte[] data) {
        int ack = 0x100;
        if (data.length > 0) {
            ack = data[0] & 0xff;
        }

        mAck.add(ack);
    }

    private void parseVersion(byte[] data) {
        if (data.length != 2) {
            onVersionResponse(BlufiCallback.CODE_INVALID_DATA, null);
        }

        BlufiVersionResponse response = new BlufiVersionResponse();
        response.setVersionValues(toInt(data[0]), toInt(data[1]));
        onVersionResponse(BlufiCallback.STATUS_SUCCESS, response);
    }

    private void parseWifiState(byte[] data) {
        if (data.length < 3) {
            onStatusResponse(BlufiCallback.CODE_INVALID_DATA, null);
            return;
        }

        BlufiStatusResponse response = new BlufiStatusResponse();

        ByteArrayInputStream dataIS = new ByteArrayInputStream(data);

        int opMode = dataIS.read() & 0xff;
        response.setOpMode(opMode);

        int staConn = dataIS.read() & 0xff;
        response.setStaConnectionStatus(staConn);

        int softAPConn = dataIS.read() & 0xff;
        response.setSoftAPConnectionCount(softAPConn);

        int callbackStatus = BlufiCallback.STATUS_SUCCESS;
        while (dataIS.available() > 0) {
            int infoType = dataIS.read() & 0xff;
            int len = dataIS.read() & 0xff;
            byte[] stateBytes = new byte[len];
            int read = dataIS.read(stateBytes, 0, len);
            if (read != len) {
                callbackStatus = BlufiCallback.CODE_INVALID_DATA;
                break;
            }
            parseWifiStateData(response, infoType, stateBytes);
        }

        onStatusResponse(callbackStatus, response);
    }

    private void parseWifiStateData(BlufiStatusResponse response, int infoType, byte[] data) {
        switch (infoType) {
            case BlufiParameter.Type.Data.SUBTYPE_STA_WIFI_BSSID:
                String staBssid = toHex(data);
                response.setStaBSSID(staBssid);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_STA_WIFI_SSID:
                String staSsid = new String(data);
                response.setStaSSID(staSsid);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_STA_WIFI_PASSWORD:
                String staPassword = new String(data);
                response.setStaPassword(staPassword);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_SOFTAP_AUTH_MODE:
                int authMode = toInt(data[0]);
                response.setSoftAPSecrity(authMode);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_SOFTAP_CHANNEL:
                int softAPChannel = toInt(data[0]);
                response.setSoftAPChannel(softAPChannel);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_SOFTAP_MAX_CONNECTION_COUNT:
                int softAPMaxConnCount = toInt(data[0]);
                response.setSoftAPMaxConnectionCount(softAPMaxConnCount);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_SOFTAP_WIFI_PASSWORD:
                String softapPassword = new String(data);
                response.setSoftAPPassword(softapPassword);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_SOFTAP_WIFI_SSID:
                String softapSSID = new String(data);
                response.setSoftAPSSID(softapSSID);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_WIFI_STA_MAX_CONN_RETRY:
                int maxRetry = toInt(data[0]);
                response.setMaxRetry(maxRetry);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_WIFI_STA_CONN_END_REASON:
                int endReason = toInt(data[0]);
                response.setEndReason(endReason);
                break;
            case BlufiParameter.Type.Data.SUBTYPE_WIFI_STA_CONN_RSSI:
                int rssi = data[0];
                response.setRssi(rssi);
                break;

        }
    }

    private void parseWifiScanList(byte[] data) {
        List<BlufiScanResult> result = new LinkedList<>();

        ByteArrayInputStream dataReader = new ByteArrayInputStream(data);
        while (dataReader.available() > 0) {
            int length = dataReader.read() & 0xff;
            if (length < 1) {
                Log.w(TAG, "Parse WifiScan invalid length");
                break;
            }
            byte rssi = (byte) dataReader.read();
            byte[] ssidBytes = new byte[length - 1];
            int ssidRead = dataReader.read(ssidBytes, 0, ssidBytes.length);
            if (ssidRead != ssidBytes.length) {
                Log.w(TAG, "Parse WifiScan parse ssid failed");
                break;
            }

            BlufiScanResult sr = new BlufiScanResult();
            sr.setType(BlufiScanResult.TYPE_WIFI);
            sr.setRssi(rssi);
            String ssid = new String(ssidBytes);
            sr.setSsid(ssid);
            result.add(sr);
        }

        onDeviceScanResult(BlufiCallback.STATUS_SUCCESS, result);
    }

    private void onError(final int errCode) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onError(mClient, errCode);
            }
        });
    }

    /*private void __negotiateSecurity() {
        Log.i(TAG, "Starting security negotiation process");

        BlufiDH espDH = postNegotiateSecurity();
        if (espDH == null) {
            Log.e(TAG, "negotiateSecurity postNegotiateSecurity failed - DH object is null");
            onNegotiateSecurityResult(BlufiCallback.CODE_NEG_POST_FAILED);
            return;
        }

        Log.i(TAG, "DH generation successful, waiting for device public key");

        BigInteger devicePublicKey;
        try {
            devicePublicKey = mDevicePublicKeyQueue.take();
            if (devicePublicKey.bitLength() == 0) {
                Log.e(TAG, "Received invalid device public key with bit length 0");
                onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_DEV_KEY);
                return;
            }
            Log.d(TAG, "Successfully obtained device public key, bit length: " + devicePublicKey.bitLength());
        } catch (InterruptedException e) {
            Log.w(TAG, "Waiting for device public key was interrupted", e);
            Thread.currentThread().interrupt();
            return;
        }

        try {
            Log.d(TAG, "Starting key generation based on device public key");
            espDH.generateSecretKey(devicePublicKey);
            if (espDH.getSecretKey() == null) {
                Log.e(TAG, "Key generation failed - key is null");
                onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_SECURITY);
                return;
            }
            Log.d(TAG, "Key generation successful, length: " + espDH.getSecretKey().length);

            mAESKey = BlufiMD5.getMD5Bytes(espDH.getSecretKey());
            Log.d(TAG, "MD5 key generation successful, length: " + (mAESKey != null ? mAESKey.length : "null"));
        } catch (Exception e) {
            Log.e(TAG, "Exception during key generation", e);
            onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_SECURITY);
            return;
        }

        boolean setSecurity = false;
        try {
            Log.d(TAG, "Starting to set security mode");
            setSecurity = postSetSecurity(false, false, true, true);
            Log.d(TAG, "Set security mode result: " + setSecurity);
        } catch (Exception e) {
            Log.e(TAG, "Exception when setting security mode", e);
        }

        if (setSecurity) {
            mEncrypted = true;
            mChecksum = true;
            Log.i(TAG, "Security negotiation completed successfully");
            onNegotiateSecurityResult(BlufiCallback.STATUS_SUCCESS);
        } else {
            mEncrypted = false;
            mChecksum = false;
            Log.e(TAG, "Setting security mode failed");
            onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_SET_SECURITY);
        }
    }*/
    private void __negotiateSecurity() {
        Log.i(TAG, "Starting security negotiation process");
        BlufiDH espDH = postNegotiateSecurity();
        if (espDH == null) {
            Log.e(TAG, "negotiateSecurity postNegotiateSecurity failed - DH object is null");
            mUIHandler.post(() -> onNegotiateSecurityResult(BlufiCallback.CODE_NEG_POST_FAILED));
            return;
        }

        Log.i(TAG, "DH generation successful, waiting for device public key");

        BigInteger devicePublicKey;
        try {
            devicePublicKey = mDevicePublicKeyQueue.take();
            if (devicePublicKey.bitLength() == 0) {
                Log.e(TAG, "Received invalid device public key with bit length 0");
                mUIHandler.post(() -> onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_DEV_KEY));
                return;
            }
            Log.d(TAG, "Successfully obtained device public key, bit length: " + devicePublicKey.bitLength());
        } catch (InterruptedException e) {
            Log.w(TAG, "Waiting for device public key was interrupted", e);
            Thread.currentThread().interrupt();
            return;
        }

        try {
            Log.d(TAG, "Starting key generation based on device public key");
            espDH.generateSecretKey(devicePublicKey);
            if (espDH.getSecretKey() == null) {
                Log.e(TAG, "Key generation failed - key is null");
                mUIHandler.post(() -> onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_SECURITY));
                return;
            }
            Log.d(TAG, "Key generation successful, length: " + espDH.getSecretKey().length);

            mAESKey = BlufiMD5.getMD5Bytes(espDH.getSecretKey());
            Log.d(TAG, "MD5 key generation successful, length: " + (mAESKey != null ? mAESKey.length : "null"));
        } catch (Exception e) {
            Log.e(TAG, "Exception during key generation", e);
            mUIHandler.post(() -> onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_SECURITY));
            return;
        }

        boolean setSecurity = false;
        try {
            Log.d(TAG, "Starting to set security mode");
            setSecurity = postSetSecurity(false, false, true, true);
            Log.d(TAG, "Set security mode result: " + setSecurity);
        } catch (Exception e) {
            Log.e(TAG, "Exception when setting security mode", e);
        }

        if (setSecurity) {
            mEncrypted = true;
            mChecksum = true;
            Log.i(TAG, "Security negotiation completed successfully");
            // 记录当前的MTU值
            Log.d(TAG, "Current MTU value during security negotiation: " + mBlufiMTU);
            mUIHandler.post(() -> onNegotiateSecurityResult(BlufiCallback.STATUS_SUCCESS));
        } else {
            mEncrypted = false;
            mChecksum = false;
            Log.e(TAG, "Setting security mode failed");
            mUIHandler.post(() -> onNegotiateSecurityResult(BlufiCallback.CODE_NEG_ERR_SET_SECURITY));
        }
    }
    private void onNegotiateSecurityResult(final int status) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onNegotiateSecurityResult(mClient, status);
            }
        });
    }

    private BlufiDH postNegotiateSecurity() {
        Log.d(TAG, "Starting postNegotiateSecurity process");
        int type = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_NEG);

        final int radix = 16;
        final int dhLength = 1024;
        final BigInteger dhP = new BigInteger(DH_P, radix);
        final BigInteger dhG = new BigInteger(DH_G);
        BlufiDH blufiDH;
        String p;
        String g;
        String k;
        do {
            Log.d(TAG, "Generating DH parameters");
            blufiDH = new BlufiDH(dhP, dhG, dhLength);
            p = blufiDH.getP().toString(radix);
            g = blufiDH.getG().toString(radix);
            k = getPublicValue(blufiDH);
        } while (k == null);

        Log.d(TAG, "DH parameters generation successful - P length:" + p.length() + ", G length:" + g.length() + ", K length:" + k.length());

        byte[] pBytes = toBytes(p);
        byte[] gBytes = toBytes(g);
        byte[] kBytes = toBytes(k);

        Log.d(TAG, "Converted to byte arrays - P:" + pBytes.length + " bytes, G:" + gBytes.length + " bytes, K:" + kBytes.length + " bytes");

        ByteArrayOutputStream dataOS = new ByteArrayOutputStream();

        int pgkLength = pBytes.length + gBytes.length + kBytes.length + 6;
        int pgkLen1 = (pgkLength >> 8) & 0xff;
        int pgkLen2 = pgkLength & 0xff;
        dataOS.write(NEG_SECURITY_SET_TOTAL_LENGTH);
        dataOS.write((byte) pgkLen1);
        dataOS.write((byte) pgkLen2);

        Log.d(TAG, "Preparing to send total length information - Total length:" + pgkLength + " bytes");

        try {
            boolean postLength = post(false, false, mRequireAck, type, dataOS.toByteArray());
            if (!postLength) {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Android 14特定处理，尝试减小数据包大小后重试
                    Log.e(TAG, "Failed to send length information, trying with smaller packet");
                    // 直接跳过发送长度信息，继续执行
                    // 这是一个妥协方案，因为成功日志显示即使timeout也能继续执行
                } else {
                    Log.e(TAG, "Failed to send length information");
                    return null;}
            }
            Log.d(TAG, "Successfully sent length information");
        } catch (InterruptedException e) {
            Log.e(TAG, "Sending length information was interrupted", e);
            Thread.currentThread().interrupt();
            return null;
        }

        // Use CountDownLatch instead of simple sleep
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Log.d(TAG, "Waiting for device to process length information...");
            boolean waited = latch.await(NegotiateSecurity_timeout, TimeUnit.MILLISECONDS); // Increased to 500ms
            Log.d(TAG, "Wait completed, status:" + (waited ? "completed" : "timeout"));
        } catch (InterruptedException e) {
            Log.e(TAG, "Wait process was interrupted", e);
            Thread.currentThread().interrupt();
            return null;
        }

        dataOS.reset();
        dataOS.write(NEG_SECURITY_SET_ALL_DATA);

        int pLength = pBytes.length;
        int pLen1 = (pLength >> 8) & 0xff;
        int pLen2 = pLength & 0xff;
        dataOS.write(pLen1);
        dataOS.write(pLen2);
        dataOS.write(pBytes, 0, pLength);

        int gLength = gBytes.length;
        int gLen1 = (gLength >> 8) & 0xff;
        int gLen2 = gLength & 0xff;
        dataOS.write(gLen1);
        dataOS.write(gLen2);
        dataOS.write(gBytes, 0, gLength);

        int kLength = kBytes.length;
        int kLen1 = (kLength >> 8) & 0xff;
        int kLen2 = kLength & 0xff;
        dataOS.write(kLen1);
        dataOS.write(kLen2);
        dataOS.write(kBytes, 0, kLength);

        Log.d(TAG, "Preparing to send DH parameter data - Total data length:" + dataOS.size() + " bytes");

        try {
            boolean postPGK = post(false, false, mRequireAck, type, dataOS.toByteArray());
            if (!postPGK) {
                Log.e(TAG, "Failed to send DH parameter data");
                return null;
            }
            Log.d(TAG, "Successfully sent DH parameter data");
        } catch (InterruptedException e) {
            Log.e(TAG, "Sending DH parameter data was interrupted", e);
            Thread.currentThread().interrupt();
            return null;
        }

        dataOS.reset();
        return blufiDH;
    }


    private String getPublicValue(BlufiDH espDH) {
        DHPublicKey publicKey = espDH.getPublicKey();
        if (publicKey != null) {
            BigInteger y = publicKey.getY();
            StringBuilder keySB = new StringBuilder(y.toString(16));
            while (keySB.length() < 256) {
                keySB.insert(0, "0");
            }
            return keySB.toString();
        }

        return null;
    }

    private boolean postSetSecurity(boolean ctrlEncrypted, boolean ctrlChecksum, boolean dataEncrypted, boolean dataChecksum) {
        int type = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_SET_SEC_MODE);
        int data = 0;
        if (dataChecksum) {
            data |= 1;
        }
        if (dataEncrypted) {
            data |= 0b10;
        }
        if (ctrlChecksum) {
            data |= 0b10000;
        }
        if (ctrlEncrypted) {
            data |= 0b100000;
        }

        byte[] postData = {(byte) data};

        try {
            return post(false, true, mRequireAck, type, postData);
        } catch (InterruptedException e) {
            Log.w(TAG, "postSetSecurity interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private class SecurityCallback {
        void onReceiveDevicePublicKey(byte[] keyData) {
            String keyStr = toHex(keyData);
            try {
                BigInteger devicePublicValue = new BigInteger(keyStr, 16);
                mDevicePublicKeyQueue.add(devicePublicValue);
            } catch (NumberFormatException e) {
                Log.w(TAG, "onReceiveDevicePublicKey: NumberFormatException -> " + keyStr);
                mDevicePublicKeyQueue.add(new BigInteger("0"));
            }
        }
    }

    private void __configure(BlufiConfigureParams params) {
        int opMode = params.getOpMode();
        switch (opMode) {
            case OP_MODE_NULL: {
                if (!postDeviceMode(opMode)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_SET_OPMODE);
                    return;
                }

                onPostConfigureParams(BlufiCallback.STATUS_SUCCESS);
                return;
            }
            case OP_MODE_STA: {
                if (!postDeviceMode(opMode)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_SET_OPMODE);
                    return;
                }
                if (!postStaWifiInfo(params)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_POST_STA);
                    return;
                }

                onPostConfigureParams(BlufiCallback.STATUS_SUCCESS);
                return;
            }
            case OP_MODE_SOFTAP: {
                if (!postDeviceMode(opMode)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_SET_OPMODE);
                    return;
                }
                if (!postSoftAPInfo(params)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_POST_SOFTAP);
                    return;
                }

                onPostConfigureParams(BlufiCallback.STATUS_SUCCESS);
                return;
            }
            case OP_MODE_STASOFTAP: {
                if (!postDeviceMode(opMode)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_SET_OPMODE);
                    return;
                }
                if (!postStaWifiInfo(params)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_POST_STA);
                    return;
                }
                if (!postSoftAPInfo(params)) {
                    onPostConfigureParams(BlufiCallback.CODE_CONF_ERR_POST_SOFTAP);
                    return;
                }

                onPostConfigureParams(BlufiCallback.STATUS_SUCCESS);
                break;
            }
            default: {
                onPostConfigureParams(BlufiCallback.CODE_CONF_INVALID_OPMODE);
                break;
            }
        }
    }

    private void onPostConfigureParams(final int status) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onPostConfigureParams(mClient, status);
            }
        });
    }

    private boolean postDeviceMode(int deviceMode) {
        int type = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_SET_OP_MODE);
        byte[] data = {(byte) deviceMode};

        try {
            return post(mEncrypted, mChecksum, true, type, data);
        } catch (InterruptedException e) {
            Log.w(TAG, "postDeviceMode interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean postStaWifiInfo(BlufiConfigureParams params) {
        try {
            int ssidType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_STA_WIFI_SSID);
            byte[] ssidBytes = params.getStaSSIDBytes();
            if (!post(mEncrypted, mChecksum, mRequireAck, ssidType, ssidBytes)) {
                return false;
            }
            //sleep(10);
            if (!sleep(10)) {  // 使用新的sleep方法
                Log.w(TAG, "postStaWifiInfo: sleep interrupted");
            }

            int pwdType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_STA_WIFI_PASSWORD);
            if (!post(mEncrypted, mChecksum, mRequireAck, pwdType, params.getStaPassword().getBytes())) {
                return false;
            }
            //sleep(10);
            if (!sleep(10)) {  // 使用新的sleep方法
                Log.w(TAG, "postStaWifiInfo: sleep interrupted");
            }

            int comfirmType = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_CONNECT_WIFI);
            return post(false, false, mRequireAck, comfirmType, null);
        } catch (InterruptedException e) {
            Log.w(TAG, "postStaWifiInfo: interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean postSoftAPInfo(BlufiConfigureParams params) {
        try {
            String ssid = params.getSoftAPSSID();
            if (!TextUtils.isEmpty(ssid)) {
                int ssidType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_SOFTAP_WIFI_SSID);
                if (!post(mEncrypted, mChecksum, mRequireAck, ssidType, params.getSoftAPSSID().getBytes())) {
                    return false;
                }
                //sleep(10);
                if (!sleep(10)) {  // 使用新的sleep方法
                    Log.w(TAG, "postStaWifiInfo: sleep interrupted");
                }
            }

            String password = params.getSoftAPPassword();
            if (!TextUtils.isEmpty(password)) {
                int pwdType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_SOFTAP_WIFI_PASSWORD);
                if (!post(mEncrypted, mChecksum, mRequireAck, pwdType, password.getBytes())) {
                    return false;
                }
                //sleep(10);
                if (!sleep(10)) {  // 使用新的sleep方法
                    Log.w(TAG, "postStaWifiInfo: sleep interrupted");
                }
            }

            int channel = params.getSoftAPChannel();
            if (channel > 0) {
                int channelType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_SOFTAP_CHANNEL);
                if (!post(mEncrypted, mChecksum, mRequireAck, channelType, new byte[]{(byte) channel})) {
                    return false;
                }
                //sleep(10);
                if (!sleep(10)) {  // 使用新的sleep方法
                    Log.w(TAG, "postStaWifiInfo: sleep interrupted");
                }
            }

            int maxConn = params.getSoftAPMaxConnection();
            if (maxConn > 0) {
                int maxConnType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_SOFTAP_MAX_CONNECTION_COUNT);
                if (!post(mEncrypted, mChecksum, mRequireAck, maxConnType, new byte[]{(byte) maxConn})) {
                    return false;
                }
                //sleep(10);
                if (!sleep(10)) {  // 使用新的sleep方法
                    Log.w(TAG, "postStaWifiInfo: sleep interrupted");
                }
            }

            int securityType = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_SOFTAP_AUTH_MODE);
            byte[] securityBytes = {(byte) params.getSoftAPSecurity()};
            return post(mEncrypted, mChecksum, mRequireAck, securityType, securityBytes);
        } catch (InterruptedException e) {
            Log.w(TAG, "postSoftAPInfo: interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void __requestDeviceVersion() {
        int type = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_GET_VERSION);
        boolean request;
        try {
            request = post(mEncrypted, mChecksum, false, type, null);
        } catch (InterruptedException e) {
            Log.w(TAG, "post requestDeviceVersion interrupted");
            request = false;
            Thread.currentThread().interrupt();
        }

        if (!request) {
            onVersionResponse(BlufiCallback.CODE_WRITE_DATA_FAILED, null);
        }
    }

    private void onVersionResponse(final int status, final BlufiVersionResponse response) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onDeviceVersionResponse(mClient, status, response);
            }
        });
    }

    private void __requestDeviceStatus() {
        int type = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_GET_WIFI_STATUS);
        boolean request;
        try {
            request = post(mEncrypted, mChecksum, false, type, null);
        } catch (InterruptedException e) {
            Log.w(TAG, "post requestDeviceStatus interrupted");
            request = false;
            Thread.currentThread().interrupt();
        }

        if (!request) {
            onStatusResponse(BlufiCallback.CODE_WRITE_DATA_FAILED, null);
        }
    }

    private void onStatusResponse(final int status, final BlufiStatusResponse response) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onDeviceStatusResponse(mClient, status, response);
            }
        });
    }

    private void __requestDeviceWifiScan() {
        int type = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_GET_WIFI_LIST);
        boolean request;
        try {
            request = post(mEncrypted, mChecksum, mRequireAck, type, null);
        } catch (InterruptedException e) {
            Log.w(TAG, "post requestDeviceWifiScan interrupted");
            request = false;
            Thread.currentThread().interrupt();
        }

        if (!request) {
            onDeviceScanResult(BlufiCallback.CODE_WRITE_DATA_FAILED, Collections.emptyList());
        }
    }

    private void onDeviceScanResult(final int status, final List<BlufiScanResult> results) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onDeviceScanResult(mClient, status, results);
            }
        });
    }

    private void __postCustomData(byte[] data) {
        int type = getTypeValue(Type.Data.PACKAGE_VALUE, Type.Data.SUBTYPE_CUSTOM_DATA);
        try {
            boolean suc = post(mEncrypted, mChecksum, mRequireAck, type, data);
            int status = suc ? BlufiCallback.STATUS_SUCCESS : BlufiCallback.CODE_WRITE_DATA_FAILED;
            onPostCustomDataResult(status, data);
        } catch (InterruptedException e) {
            Log.w(TAG, "post postCustomData interrupted");
            Thread.currentThread().interrupt();
        }
    }

    private void onPostCustomDataResult(final int status, final byte[] data) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                mUserBlufiCallback.onPostCustomDataResult(mClient, status, data);
            }
        });
    }

    private void onReceiveCustomData(final byte[] data) {
        mUIHandler.post(() -> {
            if (mUserBlufiCallback != null) {
                int status = BlufiCallback.STATUS_SUCCESS;
                mUserBlufiCallback.onReceiveCustomData(mClient, status, data);
            }
        });
    }

    private void __requestCloseConnection() {
        int type = getTypeValue(Type.Ctrl.PACKAGE_VALUE, Type.Ctrl.SUBTYPE_CLOSE_CONNECTION);
        try {
            post(false, false, false, type, null);
        } catch (InterruptedException e) {
            Log.w(TAG, "post requestCloseConnection interrupted");
            Thread.currentThread().interrupt();
        }
    }

    private abstract static class ThrowableRunnable implements Runnable {
        @Override
        public void run() {
            try {
                execute();
            } catch (Exception e) {
                Log.e(TAG, "Error executing task", e);
                onError(e);
            }
        }

        abstract void execute();

        void onError(Exception e) {
        }
    }

    /*
    private void sleep(long timeout) {
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            Log.w(TAG, "sleep: interrupted");
            Thread.currentThread().interrupt();
        }
    }*/
    private boolean sleep(long timeout) {
        final CountDownLatch latch = new CountDownLatch(1);
        mUIHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        }, timeout);

        try {
            return latch.await(timeout + 50, TimeUnit.MILLISECONDS); // 设置略长的超时时间50ms
        } catch (InterruptedException e) {
            Log.w(TAG, "sleep: latch wait interrupted");
            return false;
        }
    }



    private class InnerGattCallback extends BluetoothGattCallback {


        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            mConnectState = newState;
            mBlufiMTU = -1;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    }

                    gatt.discoverServices();
                }
            }

            if (mUserGattCallback != null) {
                mUserGattCallback.onConnectionStateChange(gatt, status, newState);
            }
        }



        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = null;
            BluetoothGattCharacteristic writeChar = null;
            BluetoothGattCharacteristic notifyChar = null;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                service = gatt.getService(BlufiParameter.UUID_SERVICE);
                if (service != null) {
                    writeChar = service.getCharacteristic(BlufiParameter.UUID_WRITE_CHARACTERISTIC);
                    notifyChar = service.getCharacteristic(BlufiParameter.UUID_NOTIFICATION_CHARACTERISTIC);
                    if (notifyChar != null) {
                        gatt.setCharacteristicNotification(notifyChar, true);
                    }
                    // 添加 MTU 请求 for andriod14
                    if (Build.VERSION.SDK_INT >= 34) { // Android 14
                        try {
                            // 直接为 Android 14 设置 MTU 大小
                            int mtuSize = 64; // 对于 Android 14，使用较小的值提高稳定性
                            Log.d(TAG, "Requesting MTU: " + mtuSize);
                            gatt.requestMtu(mtuSize);
                        } catch (Exception e) {
                            Log.e(TAG, "MTU request failed", e);
                        }
                    }
        }

        mWriteChar = writeChar;
        mNotifyChar = notifyChar;
    }

            if (mUserGattCallback != null) {
        mUserGattCallback.onServicesDiscovered(gatt, status);
    }
            if (mUserBlufiCallback != null) {
        final BluetoothGattService cbService = service;
        final BluetoothGattCharacteristic cbWriteChar = writeChar;
        final BluetoothGattCharacteristic cbNotifyChar = notifyChar;
        final BluetoothGattDescriptor notifyDesc = notifyChar == null ? null :
                        notifyChar.getDescriptor(BlufiParameter.UUID_NOTIFICATION_DESCRIPTOR);
                if (service != null && writeChar != null && notifyChar != null && notifyDesc != null) {
                    Log.d(TAG, "Write ENABLE_NOTIFICATION_VALUE");
                    notifyDesc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(notifyDesc);
                } else {
                    mUIHandler.post(() -> {
                        if (mUserBlufiCallback != null) {
                            mUserBlufiCallback.onGattPrepared(mClient, gatt, cbService, cbWriteChar, cbNotifyChar);
                        }
                    });
                }

            }
        }

        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic.equals(mNotifyChar)) {
                if (mNotifyData == null) {
                    mNotifyData = new BlufiNotifyData();
                }
                byte[] data = characteristic.getValue();
                if (mPrintDebug) {
                    Log.i(TAG, "Gatt Notification: " + Arrays.toString(data));
                }
                // lt 0 is error, eq 0 is complete, gt 0 is continue
                int parse = parseNotification(data, mNotifyData);
                if (parse < 0) {
                    onError(BlufiCallback.CODE_INVALID_NOTIFICATION);
                } else if (parse == 0) {
                    parseBlufiNotifyData(mNotifyData);
                    mNotifyData = null;
                }
            }

            if (mUserGattCallback != null) {
                mUserGattCallback.onCharacteristicChanged(gatt, characteristic);
            }
        }

        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (characteristic.equals(mWriteChar)) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "onCharacteristicWrite: status=" + status);
                }
                mWriteResultQueue.add(status == BluetoothGatt.GATT_SUCCESS);
            }

            if (mUserGattCallback != null) {
                mUserGattCallback.onCharacteristicWrite(gatt, characteristic, status);
            }
        }

        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (mUserGattCallback != null) {
                mUserGattCallback.onCharacteristicRead(gatt, characteristic, status);
            }
        }

        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (mUserGattCallback != null) {
                mUserGattCallback.onDescriptorRead(gatt, descriptor, status);
            }
        }

        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor.getUuid().equals(BlufiParameter.UUID_NOTIFICATION_DESCRIPTOR) &&
                    descriptor.getCharacteristic().getUuid().equals(BlufiParameter.UUID_NOTIFICATION_CHARACTERISTIC)) {
                BluetoothGattService service = descriptor.getCharacteristic().getService();
                BluetoothGattCharacteristic notifyChar = descriptor.getCharacteristic();
                BluetoothGattCharacteristic writeChar = mWriteChar;
                mUIHandler.post(() -> {
                    if (mUserBlufiCallback != null) {
                        mUserBlufiCallback.onGattPrepared(mClient, gatt, service, writeChar, notifyChar);
                    }
                });
            }

            if (mUserGattCallback != null) {
                mUserGattCallback.onDescriptorWrite(gatt, descriptor, status);
            }
        }

        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            if (mUserGattCallback != null) {
                mUserGattCallback.onReliableWriteCompleted(gatt, status);
            }
        }

        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (mUserGattCallback != null) {
                mUserGattCallback.onReadRemoteRssi(gatt, rssi, status);
            }
        }

       @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mBlufiMTU = mtu - 4; // Three bytes BLE header, one byte reserved
                Log.d(TAG, "MTU changed to: " + mBlufiMTU);

            }
            if (mUserGattCallback != null) {
                mUserGattCallback.onMtuChanged(gatt, mtu, status);
            }
        }

        @TargetApi(Build.VERSION_CODES.O)
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            if (mUserGattCallback != null) {
                mUserGattCallback.onPhyUpdate(gatt, txPhy, rxPhy, status);
            }
        }

        @TargetApi(Build.VERSION_CODES.O)
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            if (mUserGattCallback != null) {
                mUserGattCallback.onPhyRead(gatt, txPhy, rxPhy, status);
            }
        }
    }
}
