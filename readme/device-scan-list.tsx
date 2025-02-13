import React, { useState } from 'react';
import { ArrowLeft, RotateCw } from 'lucide-react';

const DeviceScanScreen = ({ onSelectDevice, onBack }) => {
  // 模拟设备列表数据
  const [devices, setDevices] = useState([
    { 
      id: 'BM8722470978',
      mac: '11:22:33:44:55:66',
      rssi: -65,
      lastConfigured: '12/20 15:30',
      configCount: 3,
      type: 'radar'
    },
    { 
      id: 'BM8722460903',
      mac: 'AA:BB:CC:DD:EE:FF',
      rssi: -72,
      lastConfigured: '12/20 14:25',
      configCount: 1,
      type: 'sleep'
    },
    { 
      id: 'TSBLU_12345',
      mac: '12:34:56:78:9A:BC',
      rssi: -58,
      lastConfigured: '12/19 09:45',
      configCount: 5,
      type: 'radar'
    }
  ]);

  // 设备类型过滤器
  const [deviceType, setDeviceType] = useState('');

  // 刷新设备列表
  const handleRefresh = () => {
    // 实际使用时应该调用扫描API
    console.log('Scanning for devices...');
  };

  // 过滤设备列表
  const filteredDevices = devices.filter(device => {
    if (!deviceType) return true;
    if (deviceType === 'radar') return device.id.includes('TSBLU');
    return !device.id.includes('TSBLU');
  });

  return (
    <div className="h-screen flex flex-col bg-gray-50">
      {/* 头部 */}
      <div className="flex items-center p-4 bg-white border-b">
        <button onClick={onBack} className="p-2">
          <ArrowLeft className="w-6 h-6" />
        </button>
        <h1 className="flex-1 text-center text-lg font-medium">Search Device ID</h1>
      </div>

      {/* 主体内容 */}
      <div className="flex-1 flex flex-col">
        {/* 过滤选项 */}
        <div className="p-4 bg-white border-b">
          <p className="text-gray-600 mb-3">Select Device ID (keep phone close to device)</p>
          <div className="flex gap-6">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={deviceType === 'radar'}
                onChange={(e) => setDeviceType(e.target.checked ? 'radar' : '')}
                className="w-4 h-4 rounded border-gray-300"
              />
              <span>Radar (TSBLU)</span>
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={deviceType === 'sleep'}
                onChange={(e) => setDeviceType(e.target.checked ? 'sleep' : '')}
                className="w-4 h-4 rounded border-gray-300"
              />
              <span>Sleep</span>
            </label>
          </div>
        </div>

        {/* 刷新按钮 */}
        <button 
          onClick={handleRefresh}
          className="flex items-center justify-center gap-2 p-4 text-gray-500"
        >
          Cannot find device? Click to refresh
          <RotateCw className="w-4 h-4" />
        </button>

        {/* 设备列表 */}
        <div className="flex-1 overflow-auto">
          {filteredDevices.length === 0 ? (
            <div className="p-4 text-center text-gray-500">
              No devices found
            </div>
          ) : (
            filteredDevices.map(device => (
              <button
                key={device.id}
                onClick={() => onSelectDevice(device)}
                className="w-full bg-white hover:bg-gray-50 active:bg-gray-100 border-b"
              >
                <div className="p-3">
                  <div className="flex items-center">
                    {/* 左侧设备ID: 110pt */}
                    <div className="font-medium w-[110px] shrink-0">
                      {device.id}
                    </div>

                    {/* 中间MAC和配置历史: 140pt + 弹性空间 */}
                    <div className="flex-1 min-w-[140px] px-2">
                      <div className="text-gray-500">
                        <span className="text-gray-400">MAC:</span> {device.mac}
                      </div>
                      {device.lastConfigured && (
                        <div className="text-xs text-gray-400 mt-0.5">
                          {device.lastConfigured}
                          {device.configCount > 1 && ` (${device.configCount}x)`}
                        </div>
                      )}
                    </div>

                    {/* 右侧信号强度: 65pt */}
                    <div className="w-[65px] shrink-0 flex items-center justify-end">
                      <div className={`mr-1.5 w-2 h-2 rounded-full ${
                        device.rssi > -65 ? 'bg-green-500' : 
                        device.rssi > -75 ? 'bg-yellow-500' : 
                        'bg-red-500'
                      }`} />
                      <span className="text-gray-600 text-sm whitespace-nowrap">
                        {device.rssi}
                      </span>
                    </div>
                  </div>
                </div>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default DeviceScanScreen;