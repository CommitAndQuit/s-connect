package com.suzuki.sconnect.ble;

import com.suzuki.sconnect.utils.DebugLogger;
import com.suzuki.sconnect.ble.protocol.VehicleProtocol;
import com.suzuki.sconnect.ble.protocol.VehicleProtocolFactory;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class VehicleBleScanner {
    private static final long SCAN_DURATION_MS = 10000; // 10 seconds for debugging
    private BluetoothLeScanner scanner;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ScanCallback scanCallback;
    private boolean isScanning = false;
    private String brandName;

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public interface ScanResultListener {
        void onDeviceFound(BluetoothDevice device, String deviceName, int rssi);

        void onScanComplete();
    }

    public void startScan(Context context, ScanResultListener listener) {
        DebugLogger.i("Scanner", "=== Starting BLE Scan ===");

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            DebugLogger.e("Scanner", "BluetoothAdapter is null!");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            DebugLogger.e("Scanner", "Bluetooth is not enabled!");
            return;
        }

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            DebugLogger.e("Scanner", "BluetoothLeScanner is null!");
            return;
        }

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String deviceName = result.getScanRecord() != null ? result.getScanRecord().getDeviceName()
                        : device.getName();
                int rssi = result.getRssi();

                VehicleProtocol protocol = VehicleProtocolFactory.getProtocol(brandName);

                DebugLogger.d("Scanner", String.format("Found device: %s (RSSI: %d dBm)",
                        deviceName != null ? deviceName : "Unknown", rssi));

                if (deviceName != null) {
                    DebugLogger.d("Scanner", "  Address: " + device.getAddress());
                    DebugLogger.d("Scanner", "  Type: " + getDeviceType(device.getType()));

                    if (protocol.isDeviceSupported(deviceName)) {
                        DebugLogger.i("Scanner", "✓ VEHICLE DEVICE FOUND: " + deviceName);
                        listener.onDeviceFound(device, deviceName, rssi);
                    } else {
                        DebugLogger.d("Scanner", "  Not a supported device for brand " + brandName);
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                DebugLogger.e("Scanner",
                        "Scan failed with error code: " + errorCode + " (" + getScanErrorName(errorCode) + ")");
                isScanning = false;
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        DebugLogger.i("Scanner", "Scan settings: LOW_LATENCY mode");
        DebugLogger.i("Scanner", "Scan duration: " + (SCAN_DURATION_MS / 1000) + " seconds");

        try {
            scanner.startScan(null, settings, scanCallback);
            isScanning = true;
            DebugLogger.i("Scanner", "Scan started successfully");
        } catch (SecurityException e) {
            DebugLogger.e("Scanner", "Permission denied for BLE scan", e);
            return;
        }

        // Stop scan after duration
        handler.postDelayed(() -> {
            stopScan();
            DebugLogger.i("Scanner", "=== Scan Complete ===");
            listener.onScanComplete();
        }, SCAN_DURATION_MS);
    }

    public void stopScan() {
        if (scanner != null && scanCallback != null && isScanning) {
            try {
                scanner.stopScan(scanCallback);
                isScanning = false;
                DebugLogger.i("Scanner", "Scan stopped");
            } catch (SecurityException e) {
                DebugLogger.e("Scanner", "Permission denied when stopping scan", e);
            }
        }
    }

    private String getDeviceType(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                return "Classic";
            case BluetoothDevice.DEVICE_TYPE_LE:
                return "BLE";
            case BluetoothDevice.DEVICE_TYPE_DUAL:
                return "Dual";
            default:
                return "Unknown";
        }
    }

    private String getScanErrorName(int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "APP_REGISTRATION_FAILED";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            default:
                return "UNKNOWN";
        }
    }

    public boolean isScanning() {
        return isScanning;
    }
}
