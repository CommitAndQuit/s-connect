package com.suzuki.sconnect.ui.activities;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.services.NavigationService;
import com.suzuki.sconnect.utils.MapplsRouteManager;
import com.suzuki.sconnect.utils.NavigationStateHolder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.mappls.sdk.direction.ui.DirectionFragment;
import com.mappls.sdk.direction.ui.DirectionCallback;
import com.mappls.sdk.direction.ui.model.DirectionOptions;
import com.mappls.sdk.direction.ui.model.DirectionPoint;
import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.camera.CameraUpdateFactory;
import com.mappls.sdk.maps.camera.CameraPosition;
import com.mappls.sdk.maps.geometry.LatLng;
import com.mappls.sdk.maps.annotations.Polyline;
import com.mappls.sdk.maps.annotations.PolylineOptions;
import com.mappls.sdk.maps.annotations.Marker;
import com.mappls.sdk.maps.annotations.MarkerOptions;
import com.mappls.sdk.maps.location.LocationComponent;
import com.mappls.sdk.maps.location.LocationComponentActivationOptions;
import com.mappls.sdk.maps.location.modes.CameraMode;
import com.mappls.sdk.maps.location.modes.RenderMode;
import com.mappls.sdk.services.api.directions.DirectionsCriteria;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;
import com.mappls.sdk.services.api.directions.models.DirectionsRoute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NavigationActivity extends AppCompatActivity
        implements DirectionCallback, OnMapReadyCallback {

    private static final String TAG = "NavigationActivity";

    private MapView mapView;
    private MapplsMap mapplsMap;
    private DirectionFragment directionFragment;
    private androidx.cardview.widget.CardView guidanceCard;
    private android.widget.TextView tvDistance, tvInstruction;
    private android.widget.ImageView maneuverIcon;

    private double originLat, originLng, destLat, destLng;
    private String originName, destName;

    // Route visualization
    private DirectionsRoute activeRoute;
    private List<Polyline> routePolylines = new ArrayList<>();
    private Marker destinationMarker;
    private LocationComponent locationComponent;

    // State
    private boolean isNavigationStarted = false;
    private boolean pendingTrackingMode = false;
    private MapplsRouteManager routeManager;

    private final BroadcastReceiver navUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();

            if (NavigationService.ACTION_NAV_UPDATE.equals(action)) {
                int distance = intent.getIntExtra(NavigationService.EXTRA_DISTANCE, 0);
                int turnIcon = intent.getIntExtra(NavigationService.EXTRA_TURN_ICON, 0);
                String instruction = intent.getStringExtra(NavigationService.EXTRA_INSTRUCTION);
                updateGuidanceUI(distance, turnIcon, instruction);
            } else if (NavigationService.ACTION_NAV_ARRIVED.equals(action)) {
                Toast.makeText(NavigationActivity.this, "Destination Reached!", Toast.LENGTH_LONG).show();
                stopNavigationUI();
            } else if (NavigationService.ACTION_NAV_REROUTING.equals(action)) {
                tvInstruction.setText("Rerouting...");
            } else if (NavigationService.ACTION_NAV_NEW_ROUTE.equals(action)) {
                activeRoute = NavigationStateHolder.getInstance().getCurrentRoute();
                drawRouteOnMap(activeRoute);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate called");
        setContentView(R.layout.activity_navigation);

        originLat = getIntent().getDoubleExtra("origin_lat", 0);
        originLng = getIntent().getDoubleExtra("origin_lng", 0);
        destLat = getIntent().getDoubleExtra("dest_lat", 0);
        destLng = getIntent().getDoubleExtra("dest_lng", 0);
        originName = getIntent().getStringExtra("origin_name");
        destName = getIntent().getStringExtra("destination_name");

        if (originName == null) originName = "Start";
        if (destName == null) destName = "Destination";

        initUI(savedInstanceState);

        if (destLat == 0 || destLng == 0) {
            Toast.makeText(this, "Invalid destination", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        routeManager = new MapplsRouteManager();

        IntentFilter filter = new IntentFilter();
        filter.addAction(NavigationService.ACTION_NAV_UPDATE);
        filter.addAction(NavigationService.ACTION_NAV_ARRIVED);
        filter.addAction(NavigationService.ACTION_NAV_REROUTING);
        filter.addAction(NavigationService.ACTION_NAV_NEW_ROUTE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(navUpdateReceiver, filter);
        }

        int selectedRouteIndex = getIntent().getIntExtra("selectedRouteIndex", -1);
        if (selectedRouteIndex != -1) {
            setupNavigationFromSelection(selectedRouteIndex);
        } else {
            setupNavigation();
        }
    }

    private void initUI(Bundle savedInstanceState) {
        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);

        guidanceCard = findViewById(R.id.guidance_card);
        tvDistance = findViewById(R.id.tv_distance);
        tvInstruction = findViewById(R.id.tv_instruction);
        maneuverIcon = findViewById(R.id.maneuver_icon);

        findViewById(R.id.btn_stop_nav).setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, NavigationService.class);
            stopIntent.setAction("STOP_NAVIGATION");
            startService(stopIntent);
            stopNavigationUI();
        });

        findViewById(R.id.btn_recenter).setOnClickListener(v -> recenterCamera());
    }

    private void recenterCamera() {
        if (locationComponent != null && locationComponent.isLocationComponentActivated()
                && locationComponent.getLastKnownLocation() != null) {
            android.location.Location loc = locationComponent.getLastKnownLocation();
            mapplsMap.animateCamera(CameraUpdateFactory.newLatLng(
                    new LatLng(loc.getLatitude(), loc.getLongitude())));
        } else {
            Toast.makeText(this, "Current location not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMapReady(MapplsMap mapplsMap) {
        this.mapplsMap = mapplsMap;
        Log.d(TAG, "Map is ready");

        mapplsMap.getStyle(style -> {
            if (style != null) {
                Log.d(TAG, "Map style loaded");
                enableLocationComponent();
            }
        });

        if (destLat != 0 && destLng != 0) {
            mapplsMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(destLat, destLng), 14));
        }
    }

    private void enableLocationComponent() {
        if (mapplsMap == null) return;
        try {
            locationComponent = mapplsMap.getLocationComponent();
            if (locationComponent != null && mapplsMap.getStyle() != null) {
                LocationComponentActivationOptions options = LocationComponentActivationOptions
                        .builder(this, mapplsMap.getStyle())
                        .useDefaultLocationEngine(true)
                        .build();
                locationComponent.activateLocationComponent(options);
                locationComponent.setLocationComponentEnabled(true);
                locationComponent.setRenderMode(RenderMode.GPS);
                
                if (pendingTrackingMode) {
                    pendingTrackingMode = false;
                    applyTrackingCameraMode();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enabling location component", e);
        }
    }

    private void applyTrackingCameraMode() {
        if (locationComponent == null || !locationComponent.isLocationComponentActivated()) return;
        locationComponent.setCameraMode(CameraMode.TRACKING_GPS);
        int topPadding = (int) (mapView.getHeight() * 0.65);
        mapplsMap.setPadding(0, topPadding, 0, 0);
        mapplsMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder(mapplsMap.getCameraPosition())
                        .tilt(45)
                        .zoom(18.5)
                        .build()));
    }

    @Override
    public void onMapError(int code, String message) {
        Log.e(TAG, "Map Error (" + code + "): " + message);
    }

    private void setupNavigation() {
        DirectionPoint destination = DirectionPoint.setDirection(Point.fromLngLat(destLng, destLat), "Destination", "");
        DirectionOptions.Builder builder = DirectionOptions.builder()
                .showAlternative(true)
                .steps(true)
                .showStartNavigation(true)
                .profile(DirectionsCriteria.PROFILE_BIKING)
                .annotation(Arrays.asList(DirectionsCriteria.ANNOTATION_CONGESTION, DirectionsCriteria.ANNOTATION_DURATION))
                .destination(destination);

        directionFragment = DirectionFragment.newInstance(builder.build());
        directionFragment.setDirectionCallback(this);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, directionFragment, DirectionFragment.class.getSimpleName())
                .commit();
    }

    @Override
    public void onCancel() {
        finish();
    }

    @Override
    public void onStartNavigation(DirectionPoint origin, DirectionPoint destination,
            List<DirectionPoint> waypoints, DirectionsResponse directionsResponse, int index) {
        startActualNavigation(directionsResponse.routes().get(index));
    }

    private void setupNavigationFromSelection(int index) {
        Point destination = Point.fromLngLat(destLng, destLat);
        Point origin = Point.fromLngLat(originLng != 0 ? originLng : 77.2090, originLat != 0 ? originLat : 28.6139);

        routeManager.fetchRoute(origin, destination, new MapplsRouteManager.RouteCallback() {
            @Override
            public void onRouteSuccess(DirectionsResponse response) {
                runOnUiThread(() -> startActualNavigation(response.routes().get(index)));
            }
            @Override public void onRouteStringError(String error) {}
        });
    }

    private void startActualNavigation(DirectionsRoute route) {
        if (route == null) return;
        
        isNavigationStarted = true;
        activeRoute = route;

        if (guidanceCard != null) guidanceCard.setVisibility(android.view.View.VISIBLE);

        Fragment fragment = getSupportFragmentManager().findFragmentByTag(DirectionFragment.class.getSimpleName());
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().hide(fragment).commit();
        }

        drawRouteOnMap(activeRoute);
        addDestinationMarker();

        if (locationComponent != null && locationComponent.isLocationComponentActivated()) {
            applyTrackingCameraMode();
        } else {
            pendingTrackingMode = true;
        }

        NavigationStateHolder.getInstance().setCurrentRoute(activeRoute);
        NavigationStateHolder.getInstance().setOriginDestinationNames(originName, destName);

        Intent serviceIntent = new Intent(this, NavigationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateGuidanceUI(int distanceMeters, int turnIconId, String instruction) {
        if (tvDistance != null) {
            String distText = (distanceMeters < 1000) ? String.format("In %d m", distanceMeters)
                    : String.format("In %.1f km", distanceMeters / 1000.0);
            tvDistance.setText(distText);
        }
        if (tvInstruction != null) {
            tvInstruction.setText(instruction);
        }
    }

    private void stopNavigationUI() {
        isNavigationStarted = false;
        
        for (Polyline polyline : routePolylines) {
            if (mapplsMap != null) mapplsMap.removePolyline(polyline);
        }
        routePolylines.clear();

        if (destinationMarker != null && mapplsMap != null) {
            mapplsMap.removeMarker(destinationMarker);
            destinationMarker = null;
        }

        if (locationComponent != null) {
            locationComponent.setCameraMode(CameraMode.NONE);
        }

        if (guidanceCard != null) guidanceCard.setVisibility(android.view.View.GONE);

        Fragment fragment = getSupportFragmentManager().findFragmentByTag(DirectionFragment.class.getSimpleName());
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().show(fragment).commit();
        } else {
            finish();
        }
    }

    private void drawRouteOnMap(DirectionsRoute route) {
        if (mapplsMap == null || route == null) return;

        for (Polyline polyline : routePolylines) {
            mapplsMap.removePolyline(polyline);
        }
        routePolylines.clear();

        try {
            String encodedPolyline = route.geometry();
            if (encodedPolyline == null || encodedPolyline.isEmpty()) return;

            List<LatLng> routePoints = decodePolyline(encodedPolyline);
            if (routePoints.isEmpty()) return;

            PolylineOptions polylineOptions = new PolylineOptions()
                    .addAll(routePoints)
                    .color(android.graphics.Color.parseColor("#3B82F6"))
                    .width(8f);
            routePolylines.add(mapplsMap.addPolyline(polylineOptions));
        } catch (Exception e) {
            Log.e(TAG, "Error drawing route", e);
        }
    }

    private List<LatLng> decodePolyline(String encoded) {
        List<LatLng> poly = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        try {
            while (index < len) {
                int b, shift = 0, result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));

                shift = 0; result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));

                poly.add(new LatLng((double) lat / 1E6, (double) lng / 1E6));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding polyline", e);
        }
        return poly;
    }

    private void addDestinationMarker() {
        if (mapplsMap == null || destLat == 0 || destLng == 0) return;
        try {
            if (destinationMarker != null) mapplsMap.removeMarker(destinationMarker);
            destinationMarker = mapplsMap.addMarker(new MarkerOptions()
                    .position(new LatLng(destLat, destLng))
                    .title("Destination"));
        } catch (Exception e) {
            Log.e(TAG, "Error adding destination marker", e);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mapView != null) mapView.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) mapView.onDestroy();
        try {
            unregisterReceiver(navUpdateReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }
}
