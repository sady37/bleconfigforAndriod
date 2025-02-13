import React, { useState, useEffect } from 'react';
import { Clock, ArrowLeft, RotateCw } from 'lucide-react';

// StorageManager 保持不变，继续使用原有的本地存储逻辑

const DeviceScanScreen = ({ onSelectDevice, onBack }) => {
    const [deviceType, setDeviceType] = useState('');
    const [devices, setDevices] = useState([]);
    const [scanning, setScanning] = useState(false);
    
    const recentDevices = StorageManager.getRecentDevices();
    
    const handleScan = async () => {
        setScanning(true);
        try {
            // 这里实现实际的扫描逻辑
            // 需要分别调用A/B两家SDK的扫描方法
            const devices = await Promise.all([
                scanDevicesA(), // A厂SDK扫描
                scanDevicesB()  // B厂SDK扫描
            ]);
            
            // 统一设备数据格式
            const formattedDevices = devices.flat().map(device => ({
                id: device.id,
                mac: device.mac,
                rssi: device.rssi,
                type: device.id.includes('TSBLU') ? 'A' : 
                      device.id.includes('BM') ? 'B' : 'unknown'
            }));
            
            setDevices(formattedDevices);
        } catch (error) {
            console.error('Scan failed:', error);
        }
        setScanning(false);
    };
    
    const filterDevices = (device) => {
        switch (deviceType) {
            case 'A':
                return device.type === 'A';
            case 'B':
                return device.type === 'B';
            default:
                return true; // 显示所有设备
        }
    };

    return (
        <div className="h-screen flex flex-col bg-gray-50">
            <div className="flex items-center p-4 bg-white border-b">
                <button onClick={onBack} className="p-2">
                    <ArrowLeft className="w-6 h-6" />
                </button>
                <h1 className="flex-1 text-center text-lg font-medium">Search Device ID</h1>
            </div>
            
            <div className="flex-1 flex flex-col">
                <div className="p-4 bg-white border-b">
                    <p className="text-gray-600 mb-3">Select Device Type</p>
                    <div className="flex gap-6">
                        <label className="flex items-center gap-2">
                            <input
                                type="checkbox"
                                checked={deviceType === 'A'}
                                onChange={(e) => setDeviceType(e.target.checked ? 'A' : '')}
                                className="w-4 h-4"
                            />
                            <span>A厂设备</span>
                        </label>
                        <label className="flex items-center gap-2">
                            <input
                                type="checkbox"
                                checked={deviceType === 'B'}
                                onChange={(e) => setDeviceType(e.target.checked ? 'B' : '')}
                                className="w-4 h-4"
                            />
                            <span>B厂设备</span>
                        </label>
                    </div>
                </div>
                
                <button 
                    onClick={handleScan}
                    disabled={scanning}
                    className="flex items-center justify-center gap-2 p-4 text-gray-500"
                >
                    Cannot find device? Click to refresh
                    <RotateCw className={`w-4 h-4 ${scanning ? 'animate-spin' : ''}`} />
                </button>
                
                <div className="flex-1 overflow-auto">
                    {devices.filter(filterDevices).map(device => {
                        const recentConfig = recentDevices.find(d => d.id === device.id);
                        
                        return (
                            <button
                                key={device.id}
                                onClick={() => onSelectDevice(device)}
                                className="w-full bg-white hover:bg-gray-50 active:bg-gray-100 border-b"
                            >
                                <div className="p-3">
                                    <div className="flex items-center">
                                        <div className="font-medium w-[110px] shrink-0">
                                            {device.id}
                                        </div>
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

export default DeviceScanScreen;