import React, { useState, useEffect } from 'react';
import { Clock, ArrowLeft, RotateCw } from 'lucide-react';

// 数据管理类 - 处理本地存储
class StorageManager {
    private static MAX_ITEMS = 5;
    
    // WiFi 记录
    static getRecentWiFi() {
        const stored = localStorage.getItem('recent_wifi');
        return stored ? JSON.parse(stored) : [];
    }
    
    static saveWiFi(ssid: string, password: string) {
        const existing = this.getRecentWiFi();
        const newItem = { ssid, password, lastUsed: new Date().toISOString() };
        const filtered = existing.filter(item => item.ssid !== ssid);
        filtered.unshift(newItem);
        localStorage.setItem('recent_wifi', JSON.stringify(filtered.slice(0, this.MAX_ITEMS)));
    }
    
    // 服务器记录
    static getRecentServers() {
        const stored = localStorage.getItem('recent_servers');
        return stored ? JSON.parse(stored) : [];
    }
    
    static saveServer(address: string, port: number) {
        const existing = this.getRecentServers();
        const newItem = { address, port, lastUsed: new Date().toISOString() };
        const filtered = existing.filter(
            item => !(item.address === address && item.port === port)
        );
        filtered.unshift(newItem);
        localStorage.setItem('recent_servers', JSON.stringify(filtered.slice(0, this.MAX_ITEMS)));
    }
    
    // 设备配置记录
    static getRecentDevices() {
        const stored = localStorage.getItem('recent_devices');
        return stored ? JSON.parse(stored) : [];
    }
    
    static updateDeviceConfig(id: string, mac: string) {
        const existing = this.getRecentDevices();
        const now = new Date();
        const timestamp = `${now.getMonth()+1}/${now.getDate()} ${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`;
        
        const existingDevice = existing.find(d => d.id === id);
        const newItem = {
            id,
            mac,
            lastConfigured: timestamp,
            configCount: (existingDevice?.configCount || 0) + 1
        };
        
        const filtered = existing.filter(item => item.id !== id);
        filtered.unshift(newItem);
        localStorage.setItem('recent_devices', JSON.stringify(filtered.slice(0, this.MAX_ITEMS)));
    }
}

// 主配置页面
const MainConfigScreen = () => {
    const [showScan, setShowScan] = useState(false);
    const [selectedDevice, setSelectedDevice] = useState(null);
    const [serverAddress, setServerAddress] = useState('');
    const [serverPort, setServerPort] = useState('');
    const [wifiName, setWifiName] = useState('');
    const [wifiPassword, setWifiPassword] = useState('');
    
    // 处理设备选择
    const handleDeviceSelect = (device) => {
        setSelectedDevice(device);
        setShowScan(false);
    };
    
    // 处理配置提交
    const handleSubmit = async () => {
        try {
            // 实际的配置逻辑
            
            // 保存记录
            if (wifiName && wifiPassword) {
                StorageManager.saveWiFi(wifiName, wifiPassword);
            }
            if (serverAddress && serverPort) {
                StorageManager.saveServer(serverAddress, parseInt(serverPort));
            }
            if (selectedDevice) {
                StorageManager.updateDeviceConfig(selectedDevice.id, selectedDevice.mac);
            }
            
            // 显示成功提示
            alert('Configuration successful');
        } catch (error) {
            alert('Configuration failed: ' + error.message);
        }
    };
    
    if (showScan) {
        return <DeviceScanScreen 
            onSelectDevice={handleDeviceSelect}
            onBack={() => setShowScan(false)}
        />;
    }
    
    return (
        <div className="max-w-md mx-auto p-4 space-y-6">
            <h1 className="text-2xl font-semibold text-center">BLE to Wi-Fi</h1>
            
            {/* Step 1: Device Selection */}
            <div className="space-y-2">
                <h2 className="text-xl">STEP 1</h2>
                <p className="text-gray-600">Select device ID</p>
                <button 
                    onClick={() => setShowScan(true)}
                    className="w-full p-2 text-left border rounded flex justify-between items-center"
                >
                    {selectedDevice ? selectedDevice.id : "Select device ID"}
                    <span>›</span>
                </button>
            </div>
            
            {/* Step 2: Server Configuration */}
            <div className="space-y-2">
                <h2 className="text-xl">STEP 2</h2>
                <p className="text-gray-600">Server information to be connected by the device</p>
                
                <div className="space-y-2">
                    <input
                        type="text"
                        placeholder="Server IP/URL"
                        value={serverAddress}
                        onChange={(e) => setServerAddress(e.target.value)}
                        className="w-full p-2 bg-black text-white rounded"
                    />
                    <input
                        type="text"
                        placeholder="Port"
                        value={serverPort}
                        onChange={(e) => setServerPort(e.target.value)}
                        className="w-full p-2 bg-black text-white rounded"
                    />
                    
                    {/* Recent Servers */}
                    <RecentServersDropdown onSelect={(server) => {
                        setServerAddress(server.address);
                        setServerPort(String(server.port));
                    }} />
                </div>
            </div>
            
            {/* Step 3: WiFi Configuration */}
            <div className="space-y-2">
                <h2 className="text-xl">STEP 3</h2>
                <p className="text-gray-600">Select WLAN to connect device</p>
                
                <div className="space-y-2">
                    <input
                        type="text"
                        placeholder="Network name"
                        value={wifiName}
                        onChange={(e) => setWifiName(e.target.value)}
                        className="w-full p-2 bg-black text-white rounded"
                    />
                    <input
                        type="password"
                        placeholder="Password"
                        value={wifiPassword}
                        onChange={(e) => setWifiPassword(e.target.value)}
                        className="w-full p-2 bg-black text-white rounded"
                    />
                    
                    {/* Recent WiFi Networks */}
                    <RecentWiFiDropdown onSelect={(wifi) => {
                        setWifiName(wifi.ssid);
                        setWifiPassword(wifi.password);
                    }} />
                </div>
            </div>
            
            {/* Submit Button */}
            <button
                onClick={handleSubmit}
                className="w-full bg-blue-500 text-white p-4 rounded-full text-lg"
                disabled={!selectedDevice || !serverAddress || !serverPort || !wifiName || !wifiPassword}
            >
                Pair WLAN
            </button>
        </div>
    );
};

// 设备扫描页面
const DeviceScanScreen = ({ onSelectDevice, onBack }) => {
    const [deviceType, setDeviceType] = useState('');
    const [devices, setDevices] = useState([]);
    const [scanning, setScanning] = useState(false);
    
    // 获取最近配置的设备记录
    const recentDevices = StorageManager.getRecentDevices();
    
    // 扫描设备
    const handleScan = async () => {
        setScanning(true);
        // 实际的扫描逻辑
        await new Promise(resolve => setTimeout(resolve, 1000));
        
        // 模拟数据
        setDevices([
            { 
                id: 'BM8722470978',
                mac: '11:22:33:44:55:66',
                rssi: -65,
            },
            { 
                id: 'TSBLU_12345',
                mac: '12:34:56:78:9A:BC',
                rssi: -58,
            },
            // ... 更多设备
        ]);
        setScanning(false);
    };
    
    useEffect(() => {
        handleScan();
    }, []);
    
    return (
        <div className="h-screen flex flex-col bg-gray-50">
            {/* Header */}
            <div className="flex items-center p-4 bg-white border-b">
                <button onClick={onBack} className="p-2">
                    <ArrowLeft className="w-6 h-6" />
                </button>
                <h1 className="flex-1 text-center text-lg font-medium">Search Device ID</h1>
            </div>
            
            {/* Main Content */}
            <div className="flex-1 flex flex-col">
                {/* Device Type Filter */}
                <div className="p-4 bg-white border-b">
                    <p className="text-gray-600 mb-3">
                        Select Device ID (keep phone close to device)
                    </p>
                    <div className="flex gap-6">
                        <label className="flex items-center gap-2">
                            <input
                                type="checkbox"
                                checked={deviceType === 'radar'}
                                onChange={(e) => setDeviceType(e.target.checked ? 'radar' : '')}
                                className="w-4 h-4"
                            />
                            <span>Radar (TSBLU)</span>
                        </label>
                        <label className="flex items-center gap-2">
                            <input
                                type="checkbox"
                                checked={deviceType === 'sleep'}
                                onChange={(e) => setDeviceType(e.target.checked ? 'sleep' : '')}
                                className="w-4 h-4"
                            />
                            <span>Sleep</span>
                        </label>
                    </div>
                </div>
                
                {/* Refresh Button */}
                <button 
                    onClick={handleScan}
                    disabled={scanning}
                    className="flex items-center justify-center gap-2 p-4 text-gray-500"
                >
                    Cannot find device? Click to refresh
                    <RotateCw className={`w-4 h-4 ${scanning ? 'animate-spin' : ''}`} />
                </button>
                
                {/* Device List */}
                <div className="flex-1 overflow-auto">
                    {devices
                        .filter(device => {
                            if (!deviceType) return true;
                            if (deviceType === 'radar') return device.id.includes('TSBLU');
                            return !device.id.includes('TSBLU');
                        })
                        .map(device => {
                            const recentConfig = recentDevices.find(d => d.id === device.id);
                            
                            return (
                                <button
                                    key={device.id}
                                    onClick={() => onSelectDevice(device)}
                                    className="w-full bg-white hover:bg-gray-50 active:bg-gray-100 border-b"
                                >
                                    <div className="p-3">
                                        <div className="flex items-center">
                                            {/* Device ID */}
                                            <div className="font-medium w-[110px] shrink-0">
                                                {device.id}
                                            </div>
                                            
                                            {/* MAC and Config History */}
                                            <div className="flex-1 min-w-[140px] px-2">
                                                <div className="text-gray-500">
                                                    <span className="text-gray-400">MAC:</span> {device.mac}
                                                </div>
                                                {recentConfig && (
                                                    <div className="text-xs text-gray-400 mt-0.5">
                                                        {recentConfig.lastConfigured}
                                                        {recentConfig.configCount > 1 && 
                                                         ` (${recentConfig.configCount}x)`}
                                                    </div>
                                                )}
                                            </div>
                                            
                                            {/* Signal Strength */}
                                            <div className="w-[65px] shrink-0 flex items-center justify-end">
                                                <div className={`mr-1.5 w-2 h-2 rounded-full ${
                                                    device.rssi > -65 ? 'bg-green-500' : 
                                                    device.rssi > -75 ? 'bg-yellow-500' : 
                                                    'bg-red-500'
                                                }`} />
                                                <span className="text-gray-600 text-sm">
                                                    {device.rssi}
                                                </span>
                                            </div>
                                        </div>
                                    </div>
                                </button>
                            );
                        })}
                </div>
            </div>
        </div>
    );
};

// Recent Items Dropdowns
const RecentWiFiDropdown = ({ onSelect }) => {
    const [isOpen, setIsOpen] = useState(false);
    const recentWiFi = StorageManager.getRecentWiFi();
    
    if (!recentWiFi.length) return null;
    
    return (
        <div className="relative">
            <button
                onClick={() => setIsOpen(!isOpen)}
                className="text-sm text-gray-500 flex items-center"
            >
                <Clock className="w-4 h-4 mr-1" />
                Recent Networks
            </button>
            
            {isOpen && (
                <div className="absolute z-10 w-full mt-1 bg-white border rounded-md shadow-lg">
                    {recentWiFi.map((wifi, index) => (
                        <button
                            key={index}
                            className="w-full px-4 py-2 text-left hover:bg-gray-50"
                            onClick={() => {
                                onSelect(wifi);
                                setIsOpen(false);
                            }}
                        >
                            {wifi.ssid}
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
};

const RecentServersDropdown = ({ onSelect }) => {
    const [is