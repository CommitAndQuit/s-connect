package com.suzuki.bletest;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Activity with comprehensive debug UI
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 100;

    private SuzukiBleScanner scanner;
    private BluetoothDevice selectedDevice;
    private String selectedDeviceName;

    // UI Elements
    private Button btnScan, btnConnect, btnDisconnect, btnClearLogs;
    private TextView tvStatus, tvDeviceInfo, tvVehicleData;
    private EditText etUsername;
    private ListView lvLogs;
    private ScrollView svLogs;
    private ArrayAdapter<String> logAdapter;
    private List<String> logList = new ArrayList<>();

    private BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleConnectionService.ACTION_STATE_CHANGE.equals(action)) {
                String state = intent.getStringExtra("state");
                updateStatus(state);
            } else if (BleConnectionService.ACTION_VEHICLE_DATA.equals(action)) {
                int odo = intent.getIntExtra("odometer", 0);
                float tripA = intent.getFloatExtra("tripA", 0);
                float tripB = intent.getFloatExtra("tripB", 0);
                char gear = (char) intent.getIntExtra("gear", 'N');
                int fuel = intent.getIntExtra("fuelLevel", 0);

                updateVehicleData(odo, tripA, tripB, gear, fuel);
            } else if (BleConnectionService.ACTION_ERROR.equals(action)) {
                String error = intent.getStringExtra("error");
                Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DebugLogger.i("MainActivity", "=== App Started ===");

        initializeViews();
        setupLogListener();
        checkPermissions();

        scanner = new SuzukiBleScanner();
    }

    private void initializeViews() {
        btnScan = findViewById(R.id.btnScan);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        tvStatus = findViewById(R.id.tvStatus);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        tvVehicleData = findViewById(R.id.tvVehicleData);
        etUsername = findViewById(R.id.etUsername);
        lvLogs = findViewById(R.id.lvLogs);
        svLogs = findViewById(R.id.svLogs);

        logAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, logList);
        lvLogs.setAdapter(logAdapter);

        btnScan.setOnClickListener(v -> startScan());
        btnConnect.setOnClickListener(v -> connectToVehicle());
        btnDisconnect.setOnClickListener(v -> disconnectFromVehicle());
        btnClearLogs.setOnClickListener(v -> clearLogs());

        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(false);

        etUsername.setText("TEST");
    }

    private void setupLogListener() {
        // Track if user has manually scrolled up
        final boolean[] userScrolledUp = { false };

        lvLogs.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                // When user stops scrolling, check if they're at the bottom
                if (scrollState == SCROLL_STATE_IDLE) {
                    int lastVisiblePosition = view.getLastVisiblePosition();
                    int totalItems = logAdapter.getCount();
                    userScrolledUp[0] = (lastVisiblePosition < totalItems - 1);
                }
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
                // Check if user is at bottom
                int lastVisiblePosition = firstVisibleItem + visibleItemCount;
                userScrolledUp[0] = (lastVisiblePosition < totalItemCount);
            }
        });

        DebugLogger.setListener(entry -> runOnUiThread(() -> {
            logList.add(entry.toString());
            if (logList.size() > 200) {
                logList.remove(0);
            }
            logAdapter.notifyDataSetChanged();

            // Only auto-scroll if user hasn't manually scrolled up
            if (!userScrolledUp[0]) {
                lvLogs.smoothScrollToPosition(logList.size() - 1);
            }
        }));

        // Add existing logs
        for (DebugLogger.LogEntry entry : DebugLogger.getAllLogs()) {
            logList.add(entry.toString());
        }
        logAdapter.notifyDataSetChanged();
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            // Location permission still needed for foreground service
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else {
            // Android < 12
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissions.isEmpty()) {
            DebugLogger.w("Permissions", "Requesting permissions: " + permissions);
            ActivityCompat.requestPermissions(this,
                    permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            DebugLogger.i("Permissions", "All permissions granted");
        }
    }

    private void startScan() {
        DebugLogger.i("MainActivity", "User clicked SCAN");

        // Check if Bluetooth is enabled
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            DebugLogger.e("MainActivity", "Bluetooth not enabled");
            return;
        }

        btnScan.setEnabled(false);
        btnScan.setText("Scanning...");
        tvStatus.setText("Status: Scanning for devices...");
        tvDeviceInfo.setText("Device: None");

        scanner.startScan(this, new SuzukiBleScanner.ScanResultListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device, String deviceName, int rssi) {
                runOnUiThread(() -> {
                    selectedDevice = device;
                    selectedDeviceName = deviceName;

                    tvDeviceInfo.setText(String.format("Device: %s\nAddress: %s\nRSSI: %d dBm",
                            deviceName, device.getAddress(), rssi));

                    btnConnect.setEnabled(true);
                    tvStatus.setText("Status: Device found!");

                    Toast.makeText(MainActivity.this, "Found: " + deviceName, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onScanComplete() {
                runOnUiThread(() -> {
                    btnScan.setEnabled(true);
                    btnScan.setText("Scan for Devices");

                    if (selectedDevice == null) {
                        tvStatus.setText("Status: No Suzuki devices found");
                        Toast.makeText(MainActivity.this, "No Suzuki devices found", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void connectToVehicle() {
        if (selectedDevice == null) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        String username = etUsername.getText().toString().trim();
        if (username.isEmpty()) {
            Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
            return;
        }

        DebugLogger.i("MainActivity", "User clicked CONNECT");
        DebugLogger.i("MainActivity", "Starting BLE service...");

        // Start BLE service
        Intent serviceIntent = new Intent(this, BleConnectionService.class);
        serviceIntent.putExtra("device", selectedDevice);
        serviceIntent.putExtra("deviceName", selectedDeviceName);
        serviceIntent.putExtra("userName", username);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        btnConnect.setEnabled(false);
        btnDisconnect.setEnabled(true);
        tvStatus.setText("Status: Connecting...");
    }

    private void disconnectFromVehicle() {
        DebugLogger.i("MainActivity", "User clicked DISCONNECT");

        Intent serviceIntent = new Intent(this, BleConnectionService.class);
        stopService(serviceIntent);

        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        tvStatus.setText("Status: Disconnected");
        tvVehicleData.setText("Vehicle Data: No data");
    }

    private void clearLogs() {
        DebugLogger.clearLogs();
        logList.clear();
        logAdapter.notifyDataSetChanged();
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus(String state) {
        runOnUiThread(() -> {
            tvStatus.setText("Status: " + state);
        });
    }

    private void updateVehicleData(int odo, float tripA, float tripB, char gear, int fuel) {
        runOnUiThread(() -> {
            String data = String.format(
                    "Odometer: %d km\n" +
                            "Trip A: %.1f km\n" +
                            "Trip B: %.1f km\n" +
                            "Gear: %c\n" +
                            "Fuel: %d bars",
                    odo, tripA, tripB, gear, fuel);
            tvVehicleData.setText(data);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleConnectionService.ACTION_STATE_CHANGE);
        filter.addAction(BleConnectionService.ACTION_VEHICLE_DATA);
        filter.addAction(BleConnectionService.ACTION_ERROR);

        // Android 14+ (API 34) requires explicit RECEIVER_NOT_EXPORTED flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(serviceReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanner.stopScan();
        DebugLogger.i("MainActivity", "=== App Destroyed ===");
    }
}
