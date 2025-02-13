package com.ble.confignet;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ble.confignet.databinding.ActivityDeviceScanBinding;
import com.ble.confignet.databinding.ItemDeviceBinding;

import java.util.ArrayList;
import java.util.List;

public class DeviceScanActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 1;

    private ActivityDeviceScanBinding binding;
    private UnifiedBleManager bleManager;
    private DeviceAdapter adapter;
    private List<UnifiedBleManager.UnifiedBleDevice> devices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupUI();
        setupBleManager();
        checkPermissions();
    }

    private void setupUI() {
        devices = new ArrayList<>();
        adapter = new DeviceAdapter();
        binding.recyclerView.setAdapter(adapter);

        binding.buttonScan.setOnClickListener(v -> startScan());
        binding.radioGroupType.setOnCheckedChangeListener((group, checkedId) -> {
            devices.clear();
            adapter.notifyDataSetChanged();
        });

        binding.swipeRefresh.setOnRefreshListener(this::startScan);
    }

    private void setupBleManager() {
        bleManager = new UnifiedBleManager(this);
        bleManager.setCallback(new UnifiedBleManager.UnifiedBleCallback() {
            @Override
            public void onDeviceFound(UnifiedBleManager.UnifiedBleDevice device) {
                if (!devices.contains(device)) {
                    devices.add(device);
                    adapter.notifyItemInserted(devices.size() - 1);
                }
            }

            @Override
            public void onConnected(UnifiedBleManager.UnifiedBleDevice device) {
                binding.swipeRefresh.setRefreshing(false);
                // Handle device connection success
            }

            @Override
            public void onError(UnifiedBleManager.ErrorCode code) {
                binding.swipeRefresh.setRefreshing(false);
                Toast.makeText(DeviceScanActivity.this, 
                    "Error: " + code.toString(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toArray(new String[0]),
            REQUEST_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startScan();
            }
        }
    }

    private void startScan() {
        devices.clear();
        adapter.notifyDataSetChanged();
        binding.swipeRefresh.setRefreshing(true);

        UnifiedBleManager.DeviceType type = UnifiedBleManager.DeviceType.ALL;
        if (binding.radioA.isChecked()) {
            type = UnifiedBleManager.DeviceType.A;
        } else if (binding.radioB.isChecked()) {
            type = UnifiedBleManager.DeviceType.B;
        }

        bleManager.startScan(type);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.stopScan();
    }

    private class DeviceHolder extends RecyclerView.ViewHolder {
        private final ItemDeviceBinding binding;

        DeviceHolder(ItemDeviceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(UnifiedBleManager.UnifiedBleDevice device) {
            binding.textName.setText(device.getName());
            binding.textMac.setText(device.getMac());
            binding.textRssi.setText(String.valueOf(device.getRssi()));

            // Set signal strength indicator
            int signalStrength;
            if (device.getRssi() > -65) {
                signalStrength = R.drawable.ic_signal_strong;
            } else if (device.getRssi() > -75) {
                signalStrength = R.drawable.ic_signal_medium;
            } else {
                signalStrength = R.drawable.ic_signal_weak;
            }
            binding.imageSignal.setImageResource(signalStrength);

            itemView.setOnClickListener(v -> {
                bleManager.stopScan();
                bleManager.connect(device);
            });
        }
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceHolder> {
        @NonNull
        @Override
        public DeviceHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemDeviceBinding binding = ItemDeviceBinding.inflate(
                getLayoutInflater(), parent, false);
            return new DeviceHolder(binding);
        }

