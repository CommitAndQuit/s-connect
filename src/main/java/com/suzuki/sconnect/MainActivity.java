package com.suzuki.sconnect;

import android.Manifest;
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

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.Style;
import com.mappls.sdk.maps.camera.CameraUpdateFactory;
import com.mappls.sdk.maps.geometry.LatLng;
import com.mappls.sdk.maps.location.LocationComponent;
import com.mappls.sdk.maps.location.LocationComponentActivationOptions;
import com.mappls.sdk.maps.location.modes.CameraMode;
import com.mappls.sdk.maps.location.modes.RenderMode;
import com.mappls.sdk.services.account.MapplsAccountManager;
import com.mappls.sdk.maps.Mappls;
import com.mappls.sdk.plugins.places.autocomplete.ui.PlaceAutocompleteFragment;
import com.mappls.sdk.plugins.places.autocomplete.ui.PlaceSelectionListener;
import com.mappls.sdk.services.api.autosuggest.model.ELocation;
import com.mappls.sdk.maps.annotations.MarkerOptions;
import com.mappls.sdk.maps.location.permissions.PermissionsManager;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final int REQUEST_PERMISSIONS = 100;

    private SuzukiBleScanner scanner;
    private BluetoothDevice selectedDevice;

    // UI Elements
    private ImageButton btnPower, btnSettings;
    private TextView tvDeviceName, tvOdoValue, tvFuelValue, tvGearValue, tvTripAValue, tvTripBValue;
    private ImageView ivBluetoothStatus;
    private View mapCard;
    private MapView mapView;
    private MapplsMap mapplsMap;
    private NestedScrollView nestedScrollView;

    // Device Selection Sheet
    private BottomSheetDialog bottomSheetDialog;
    private DeviceAdapter deviceAdapter;
    private List<BluetoothDevice> discoveredDevices = new ArrayList<>();

    private BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleConnectionService.ACTION_STATE_CHANGE.equals(action)) {
                String state = intent.getStringExtra("state");
                updateConnectionUI(state);
            } else if (BleConnectionService.ACTION_VEHICLE_DATA.equals(action)) {
                int odo = intent.getIntExtra("odometer", 0);
                float tripA = intent.getFloatExtra("tripA", 0);
                float tripB = intent.getFloatExtra("tripB", 0);
                char gear = (char) intent.getIntExtra("gear", 'N');
                int fuel = intent.getIntExtra("fuelLevel", 0);

                updateVehicleData(odo, tripA, tripB, gear, fuel);
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
        setupScrollAnimation();
        setupSearch();

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

        btnPower.setOnClickListener(v -> showDeviceSelectionSheet());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

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
        nestedScrollView.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    // As user scrolls down, expand the map card
                    float maxScroll = 500f; // threshold for full expansion
                    float progress = Math.min(scrollY / maxScroll, 1.0f);

                    // Adjust map card height based on scroll
                    ViewGroup.LayoutParams params = mapCard.getLayoutParams();
                    int baseHeight = (int) (400 * getResources().getDisplayMetrics().density);
                    int maxHeight = v.getHeight(); // Full screen height
                    params.height = baseHeight + (int) ((maxHeight - baseHeight - 100) * progress);
                    mapCard.setLayoutParams(params);
                });
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
        serviceIntent.putExtra("userName", "USER"); // Default username for welcome message

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateConnectionUI(String state) {
        runOnUiThread(() -> {
            tvDeviceName.setText(state);
            if (state.contains("Connected")) {
                ivBluetoothStatus.setAlpha(1.0f);
                ivBluetoothStatus.setColorFilter(Color.parseColor("#10B981"));
                btnPower.setColorFilter(Color.parseColor("#10B981"));
            } else {
                ivBluetoothStatus.setAlpha(0.3f);
                ivBluetoothStatus.setColorFilter(Color.parseColor("#EF4444"));
                btnPower.setColorFilter(null);
            }
        });
    }

    private void updateVehicleData(int odo, float tripA, float tripB, char gear, int fuel) {
        runOnUiThread(() -> {
            tvOdoValue.setText(String.format("%,d", odo));

            // Convert 1-6 bars to percentage (roughly)
            int fuelPercent = Math.min(100, Math.round((fuel / 6.0f) * 100));
            tvFuelValue.setText(String.format("%d%%", fuelPercent));

            tvGearValue.setText(String.valueOf(gear));
            tvTripAValue.setText(String.format("%.1f km", tripA));
            tvTripBValue.setText(String.format("%.1f km", tripB));
        });
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

    private void setupSearch() {
        PlaceAutocompleteFragment autocompleteFragment = (PlaceAutocompleteFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mappls_autocomplete_fragment);

        if (autocompleteFragment != null) {
            autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {
                @Override
                public void onPlaceSelected(ELocation eLocation) {
                    if (mapplsMap != null && eLocation != null && eLocation.latitude != null
                            && eLocation.longitude != null) {
                        LatLng latLng = new LatLng(eLocation.latitude, eLocation.longitude);
                        mapplsMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14.0));
                        mapplsMap.addMarker(new MarkerOptions().position(latLng).title(eLocation.placeName));
                    }
                }

                @Override
                public void onCancel() {
                }
            });
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
    }
}
