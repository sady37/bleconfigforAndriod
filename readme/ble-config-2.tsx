import React, { useState } from 'react';
import { ArrowLeft, RotateCw, LucideWifi } from 'lucide-react';

const BleConfigApp = () => {
  const [currentView, setCurrentView] = useState('main'); // 'main' or 'scan'

  return (
    <div className="min-h-screen bg-gray-50">
      {currentView === 'main' ? (
        <MainConfig onScanClick={() => setCurrentView('scan')} />
      ) : (
        <ScanView onBack={() => setCurrentView('main')} />
      )}
    </div>
  );
};

const MainConfig = ({ onScanClick }) => {
  const [serverIp, setServerIp] = useState('');
  const [serverPort, setServerPort] = useState('');
  const [wifiSsid, setWifiSsid] = useState('');
  const [wifiPassword, setWifiPassword] = useState('');

  return (
    <div className="max-w-lg mx-auto p-4 space-y-6">
      {/* Device Selection */}
      <section className="bg-white p-4 rounded-lg shadow">
        <h2 className="text-lg font-semibold mb-2">Device Selection</h2>
        <button 
          onClick={onScanClick}
          className="w-full p-3 border rounded-lg flex justify-between items-center hover:bg-gray-50"
        >
          <span className="text-gray-500">Select a device...</span>
          <span className="text-gray-400">›</span>
        </button>
      </section>

      {/* Server Configuration */}
      <section className="bg-white p-4 rounded-lg shadow">
        <h2 className="text-lg font-semibold mb-2">Server Configuration</h2>
        <div className="space-y-3">
          <input
            type="text"
            placeholder="Server IP/Address"
            value={serverIp}
            onChange={(e) => setServerIp(e.target.value)}
            className="w-full p-2 border rounded-lg"
          />
          <input
            type="text"
            placeholder="Port"
            value={serverPort}
            onChange={(e) => setServerPort(e.target.value)}
            className="w-full p-2 border rounded-lg"
          />
        </div>
      </section>

      {/* WiFi Configuration */}
      <section className="bg-white p-4 rounded-lg shadow">
        <h2 className="text-lg font-semibold mb-2">WiFi Configuration</h2>
        <div className="space-y-3">
          <input
            type="text"
            placeholder="WiFi SSID"
            value={wifiSsid}
            onChange={(e) => setWifiSsid(e.target.value)}
            className="w-full p-2 border rounded-lg"
          />
          <input
            type="password"
            placeholder="WiFi Password"
            value={wifiPassword}
            onChange={(e) => setWifiPassword(e.target.value)}
            className="w-full p-2 border rounded-lg"
          />
        </div>
      </section>

      <button className="w-full bg-blue-500 text-white p-4 rounded-lg font-medium hover:bg-blue-600">
        Configure Device
      </button>
    </div>
  );
};

const ScanView = ({ onBack }) => {
  const [deviceType, setDeviceType] = useState('radar');
  const [devices] = useState([
    { id: 'TSBLU_001', mac: '00:11:22:33:44:55', rssi: -65, lastConfig: '02/15 14:30' },
    { id: 'SLEEP_002', mac: 'AA:BB:CC:DD:EE:FF', rssi: -72, lastConfig: '02/14 09:15' },
  ]);

  return (
    <div className="h-screen flex flex-col">
      {/* Header */}
      <div className="bg-white border-b px-4 py-3 flex items-center">
        <button onClick={onBack} className="p-2">
          <ArrowLeft className="w-6 h-6" />
        </button>
        <h1 className="text-lg font-medium flex-1 text-center">Scan Devices</h1>
      </div>

      {/* Device Type Filter */}
      <div className="bg-white border-b p-4">
        <div className="flex items-center gap-4">
          <label className="flex items-center gap-2 whitespace-nowrap">
            <input
              type="radio"
              checked={deviceType === 'radar'}
              onChange={() => setDeviceType('radar')}
              className="w-4 h-4"
            />
            <span>Radar</span>
          </label>
          
          <input
            type="text"
            placeholder="TSBLU"
            defaultValue="TSBLU"
            className="w-32 p-2 border rounded"
          />
          
          <label className="flex items-center gap-2 whitespace-nowrap">
            <input
              type="radio"
              checked={deviceType === 'sleepboard'}
              onChange={() => setDeviceType('sleepboard')}
              className="w-4 h-4"
            />
            <span>SleepBoard</span>
          </label>
        </div>
      </div>

      {/* Refresh Button */}
      <button className="flex items-center justify-center gap-2 p-4 text-gray-500">
        <RotateCw className="w-4 h-4" />
        Refresh
      </button>

      {/* Device List */}
      <div className="flex-1 overflow-auto">
        {devices.map(device => (
          <div key={device.id} className="border-b p-4 bg-white">
            <div className="flex justify-between items-center">
              <div>
                <div className="font-medium">{device.id}</div>
                <div className="text-sm text-gray-500">
                  {device.mac} • Last config: {device.lastConfig}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <LucideWifi className="w-4 h-4" />
                <span className="text-sm">{device.rssi} dBm</span>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default BleConfigApp;