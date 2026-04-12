package com.suzuki.sconnect.ui.activities;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;
import com.suzuki.sconnect.ble.SuzukiBleScanner;
import com.suzuki.sconnect.ui.adapters.DeviceAdapter;

import android.Manifest;
import android.app.ActivityOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.SharedPreferences;
import android.text.format.DateUtils;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.Style;
import com.mappls.sdk.maps.camera.CameraPosition;
import com.mappls.sdk.maps.camera.CameraUpdateFactory;
import com.mappls.sdk.maps.geometry.LatLng;
import com.mappls.sdk.maps.location.LocationComponent;
import com.mappls.sdk.maps.location.LocationComponentActivationOptions;
import com.mappls.sdk.maps.location.modes.CameraMode;
import com.mappls.sdk.maps.location.modes.RenderMode;
import com.mappls.sdk.services.account.MapplsAccountManager;
import com.mappls.sdk.maps.Mappls;
import com.mappls.sdk.maps.location.permissions.PermissionsManager;

import io.realm.Realm;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final int REQUEST_PERMISSIONS = 100;

    private SuzukiBleScanner scanner;
    private BluetoothDevice selectedDevice;

    // UI Elements
    private ImageButton btnPower, btnSettings;
    private TextView tvDeviceName, tvOdoValue, tvFuelValue, tvGearValue, tvTripAValue, tvTripBValue, tvSpeedValue;
    private TextView tvMileageValue;        // P3: live mileage
    private TextView tvLastParkedTime;      // P1: timestamp on dashboard card
    private TextView tvTripCount;           // P2: trip count on dashboard card
    private MaterialCardView cardLastParked, cardTripHistory;
    private ImageView ivBluetoothStatus;
    private View mapCard;
    private MapView mapView;
    private MapplsMap mapplsMap;
    private NestedScrollView nestedScrollView;


    // Device Selection Sheet
    private BottomSheetDialog bottomSheetDialog;
    private DeviceAdapter deviceAdapter;
    private List<BluetoothDevice> discoveredDevices = new ArrayList<>();

    // Connection state tracking
    private boolean isConnected = false;

    private BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleConnectionService.ACTION_STATE_CHANGE.equals(action)) {
                String state = intent.getStringExtra("state");
                updateConnectionUI(state);
            } else if (BleConnectionService.ACTION_VEHICLE_DATA.equals(action)) {
                int speed = intent.getIntExtra("speed", 0);
                int odo = intent.getIntExtra("odometer", 0);
                float tripA = intent.getFloatExtra("tripA", 0);
                float tripB = intent.getFloatExtra("tripB", 0);
                char gear = (char) intent.getIntExtra("gear", 'N');
                int fuel = intent.getIntExtra("fuelLevel", 0);
                float mileageKmL = intent.getFloatExtra("mileageKmL", 0f); // P3

                updateVehicleData(speed, odo, tripA, tripB, gear, fuel, mileageKmL);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize Mappls SDK
        MapplsAccountManager.getInstance().setRestAPIKey(getString(R.string.mappls_rest_api_key));
        MapplsAccountManager.getInstance().setMapSDKKey(getString(R.string.mappls_map_sdk_key));
        MapplsAccountManager.getInstance().setAtlasClientId(getString(R.string.mappls_client_id));
        MapplsAccountManager.getInstance().setAtlasClientSecret(getString(R.string.mappls_client_secret));
        Mappls.getInstance(this);

        setContentView(R.layout.activity_main);

        initializeViews(savedInstanceState);
        checkPermissions();

        scanner = new SuzukiBleScanner();
    }

    private void initializeViews(Bundle savedInstanceState) {
        btnPower = findViewById(R.id.btnPower);
        btnSettings = findViewById(R.id.btnSettings);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvOdoValue = findViewById(R.id.tvOdoValue);
        tvFuelValue = findViewById(R.id.tvFuelValue);
        tvGearValue = findViewById(R.id.tvGearValue);
        tvTripAValue = findViewById(R.id.tvTripAValue);
        tvTripBValue = findViewById(R.id.tvTripBValue);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        tvMileageValue = findViewById(R.id.tvMileageValue);      // P3
        tvLastParkedTime = findViewById(R.id.tvLastParkedTime);    // P1
        tvTripCount = findViewById(R.id.tvTripCount);              // P2
        cardLastParked = findViewById(R.id.cardLastParked);        // P1
        cardTripHistory = findViewById(R.id.cardTripHistory);       // P2
        ivBluetoothStatus = findViewById(R.id.ivBluetoothStatus);
        mapCard = findViewById(R.id.mapCard);
        mapView = findViewById(R.id.map_view);

        if (savedInstanceState != null) {
            mapView.onCreate(savedInstanceState);
        } else {
            mapView.onCreate(null);
        }
        mapView.getMapAsync(this);

        nestedScrollView = findViewById(R.id.nestedScrollView);

        btnPower.setOnClickListener(v -> {
            if (isConnected) {
                disconnectFromVehicle();
            } else {
                showDeviceSelectionSheet();
            }
        });
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Open full-screen map when map preview is clicked
        mapCard.setOnClickListener(v -> openFullScreenMap());

        // P1: Last Parked Location card
        cardLastParked.setOnClickListener(v ->
                startActivity(new Intent(this, LastParkedLocationActivity.class)));

        // P2: Trip History card
        cardTripHistory.setOnClickListener(v ->
                startActivity(new Intent(this, TripHistoryActivity.class)));

        // Setup scroll listener to open full-screen on scroll up
        setupScrollAnimation();

        // Setup Bottom Sheet
        bottomSheetDialog = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.device_selection_sheet, null);
        bottomSheetDialog.setContentView(sheetView);

        RecyclerView rvDevices = sheetView.findViewById(R.id.rvDevices);
        ProgressBar pbScanning = sheetView.findViewById(R.id.pbScanning);
        sheetView.findViewById(R.id.btnCancelScan).setOnClickListener(v -> bottomSheetDialog.dismiss());

        deviceAdapter = new DeviceAdapter(discoveredDevices, device -> {
            selectedDevice = device;
            connectToVehicle(device);
            bottomSheetDialog.dismiss();
        });

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(deviceAdapter);
    }

    private void setupScrollAnimation() {
        final float scrollThreshold = 200f; // Scroll this many pixels to trigger full-screen

        nestedScrollView.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    // When user scrolls up past threshold, open full-screen map
                    if (scrollY > scrollThreshold && scrollY > oldScrollY) {
                        // Remove listener to prevent multiple triggers
                        nestedScrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) null);

                        // Open full-screen map
                        openFullScreenMap();
                    }
                });
    }

    private void openFullScreenMap() {
        Intent intent = new Intent(this, FullScreenMapActivity.class);

        // Pass current map state for seamless transition
        if (mapplsMap != null) {
            LatLng currentPosition = mapplsMap.getCameraPosition().target;
            double currentZoom = mapplsMap.getCameraPosition().zoom;
            intent.putExtra("latitude", currentPosition.getLatitude());
            intent.putExtra("longitude", currentPosition.getLongitude());
            intent.putExtra("zoom", currentZoom);
        }

        // Shared element transition for seamless animation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(
                    this,
                    mapView,
                    "mapTransition");
            startActivity(intent, options.toBundle());
        } else {
            startActivity(intent);
        }
    }

    private void showDeviceSelectionSheet() {
        discoveredDevices.clear();
        deviceAdapter.notifyDataSetChanged();
        bottomSheetDialog.show();

        ProgressBar pb = bottomSheetDialog.findViewById(R.id.pbScanning);
        if (pb != null)
            pb.setVisibility(View.VISIBLE);

        scanner.startScan(this, new SuzukiBleScanner.ScanResultListener() {
            @Override
            public void onDeviceFound(BluetoothDevice device, String deviceName, int rssi) {
                runOnUiThread(() -> {
                    if (!discoveredDevices.contains(device)) {
                        discoveredDevices.add(device);
                        deviceAdapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onScanComplete() {
                runOnUiThread(() -> {
                    if (pb != null)
                        pb.setVisibility(View.GONE);
                });
            }
        });
    }

    private void connectToVehicle(BluetoothDevice device) {
        tvDeviceName.setText("Connecting...");
        ivBluetoothStatus.setAlpha(0.5f);

        Intent serviceIntent = new Intent(this, BleConnectionService.class);
        serviceIntent.putExtra("device", device);
        serviceIntent.putExtra("deviceName", device.getName());
        String savedUser = getSharedPreferences("SConnectPrefs", MODE_PRIVATE).getString("user_name", "USER");
        serviceIntent.putExtra("userName", savedUser);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateConnectionUI(String state) {
        runOnUiThread(() -> {
            tvDeviceName.setText(state);
            if (state.equals("CONNECTED") || state.equals("READY")) {
                isConnected = true;
                ivBluetoothStatus.setAlpha(1.0f);
                ivBluetoothStatus.setColorFilter(Color.parseColor("#10B981"));
                btnPower.setColorFilter(Color.parseColor("#10B981"));

                // Update device name to show connected device
                if (selectedDevice != null && selectedDevice.getName() != null) {
                    tvDeviceName.setText(selectedDevice.getName());
                }
            } else if (state.equals("DISCONNECTED")) {
                isConnected = false;
                ivBluetoothStatus.setAlpha(0.3f);
                ivBluetoothStatus.setColorFilter(Color.parseColor("#EF4444"));
                btnPower.setColorFilter(null);
                tvDeviceName.setText("Disconnected");
            } else if (state.equals("CONNECTING")) {
                isConnected = false;
                ivBluetoothStatus.setAlpha(0.5f);
                ivBluetoothStatus.setColorFilter(Color.parseColor("#F59E0B"));
                btnPower.setColorFilter(null);
                tvDeviceName.setText("Connecting...");
            }
        });
    }

    private void updateVehicleData(int speed, int odo, float tripA, float tripB, char gear, int fuel, float mileageKmL) {
        runOnUiThread(() -> {
            if (tvSpeedValue != null)
                tvSpeedValue.setText(String.format("%d km/h", speed));
            tvOdoValue.setText(String.format("%,d", odo));

            // Convert 1-6 bars to percentage (roughly)
            int fuelPercent = Math.min(100, Math.round((fuel / 6.0f) * 100));
            tvFuelValue.setText(String.format("%d%%", fuelPercent));

            tvGearValue.setText(String.valueOf(gear));
            tvTripAValue.setText(String.format("%.1f km", tripA));
            tvTripBValue.setText(String.format("%.1f km", tripB));

            // P3: Update mileage display
            if (tvMileageValue != null) {
                tvMileageValue.setText(mileageKmL > 0
                        ? String.format("%.1f", mileageKmL)
                        : "--");
            }
        });
    }

    private void disconnectFromVehicle() {
        // Stop the BLE service
        Intent serviceIntent = new Intent(this, BleConnectionService.class);
        stopService(serviceIntent);

        // Update UI immediately
        isConnected = false;
        tvDeviceName.setText("Disconnected");
        ivBluetoothStatus.setAlpha(0.3f);
        ivBluetoothStatus.setColorFilter(Color.parseColor("#EF4444"));
        btnPower.setColorFilter(null);

        // Reset vehicle data including mileage
        if (tvSpeedValue != null)
            tvSpeedValue.setText("-- km/h");
        tvOdoValue.setText("--");
        tvFuelValue.setText("--");
        tvGearValue.setText("-");
        tvTripAValue.setText("-- km");
        tvTripBValue.setText("-- km");
        if (tvMileageValue != null) tvMileageValue.setText("--"); // P3

        Toast.makeText(this, "Disconnected from vehicle", Toast.LENGTH_SHORT).show();
    }

    /**
     * P1: Refresh last parked time on the dashboard card from SharedPrefs.
     * P2: Refresh trip count from Realm DB.
     * Called onResume so the card always shows current data.
     */
    private void refreshDashboardCards() {
        // P1: Last Parked time
        SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        long parkedTime = prefs.getLong("last_parked_time", 0L);
        if (tvLastParkedTime != null) {
            if (parkedTime > 0) {
                CharSequence timeAgo = DateUtils.getRelativeTimeSpanString(
                        parkedTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
                tvLastParkedTime.setText(timeAgo);
            } else {
                tvLastParkedTime.setText("Not saved");
            }
        }

        // P2: Trip count
        if (tvTripCount != null) {
            try {
                Realm realm = Realm.getDefaultInstance();
                long count = realm.where(com.suzuki.sconnect.data.model.TripRecord.class)
                        .equalTo("status", "COMPLETED").count();
                realm.close();
                tvTripCount.setText(count > 0
                        ? count + " trip" + (count == 1 ? "" : "s")
                        : "No trips");
            } catch (Exception e) {
                tvTripCount.setText("No trips");
            }
        }
    }
    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        List<String> toRequest = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }

        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onMapReady(MapplsMap map) {
        this.mapplsMap = map;

        // Set default zoom to show ~4km x 4km area
        LatLng defaultLocation = new LatLng(28.6139, 77.2090); // Default to Delhi
        map.setCameraPosition(new CameraPosition.Builder()
                .target(defaultLocation)
                .zoom(13.0)
                .build());

        map.getStyle(new Style.OnStyleLoaded() {
            @Override
            public void onStyleLoaded(@androidx.annotation.NonNull Style style) {
                enableLocationComponent(style);
            }
        });
    }

    @Override
    public void onMapError(int errorCode, String errorMessage) {
        // Handle map error
    }

    private void enableLocationComponent(Style style) {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            LocationComponent locationComponent = mapplsMap.getLocationComponent();
            LocationComponentActivationOptions options = LocationComponentActivationOptions.builder(this, style)
                    .build();
            locationComponent.activateLocationComponent(options);
            locationComponent.setLocationComponentEnabled(true);
            locationComponent.setCameraMode(CameraMode.TRACKING);
            locationComponent.setRenderMode(RenderMode.COMPASS);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        unregisterReceiver(serviceReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleConnectionService.ACTION_STATE_CHANGE);
        filter.addAction(BleConnectionService.ACTION_VEHICLE_DATA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }

        // Re-enable scroll listener when returning from full-screen map
        setupScrollAnimation();

        // P1 + P2: Refresh Last Parked time and Trip count every time we return here
        refreshDashboardCards();
    }
}
