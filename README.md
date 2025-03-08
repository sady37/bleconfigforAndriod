WiseFido BleconfigforAndriod
20250226 v1.0
  1.sleepboard configure wifi/server, query device history
  2.radar config wifi, server, which can individual configure or together; when disconnet server, query device statu:wifi, history server 
20250305   单实例模式
    for andriod 14, use CountDownLatch  replace sleep(),避免线程被意外中断，特别是安全协商步骤
    20250306
    ScanActivity 扫描到设备后，将 BluetoothDevice 保存到单例中
    ScanActivity 通过 Intent 只传递简单的 DeviceInfo 对象（不包含 originalDevice）
    MainActivity 接收到 DeviceInfo 后，从单例中获取之前保存的 BluetoothDevice 对象
    使用这个 BluetoothDevice 对象进行后续操作
    为避免序列化序列化 BluetoothDevice 对象
    1.首先，简化 DeviceInfo 类，移除 originalDevice 字段：
    2.创建一个单例类来保存原始设备对象：
    3.在 ScanActivity 中，使用这个单例来保存设备对象：
    4.在 MainActivity 中，从单例获取原始设备对象：
    5.在 RadarBleManager 中添加使用原始设备的方法：
    6.当设备不再需要时清理单例
    ./gradlew clean
    ./gradlew build

20250308
    修改Esp BlufiClientImpl.java 解决andriod 14下安全协商bug
    解决办法andriod13(sdk33) default mtu,  andriod14(sdk34) mtu=64