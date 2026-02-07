package com.suzuki.sconnect.ui.activities;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;

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
import com.mappls.sdk.services.api.directions.models.LegStep;
import com.mappls.sdk.services.api.directions.models.RouteLeg;

import java.util.Arrays;

public class NavigationActivity extends AppCompatActivity
        implements DirectionCallback, com.suzuki.sconnect.utils.NavigationSession.NavigationUpdateListener,
        OnMapReadyCallback {

    private MapView mapView;
    private MapplsMap mapplsMap;
    private DirectionFragment directionFragment;
    private androidx.cardview.widget.CardView guidanceCard;
    private android.widget.TextView tvDistance, tvInstruction;
    private android.widget.ImageView maneuverIcon;

    private double originLat, originLng, destLat, destLng;
    private com.suzuki.sconnect.utils.NavigationSession navigationSession;
    private android.location.LocationManager locationManager;
    private android.location.LocationListener locationListener;

    // Route visualization
    private DirectionsRoute activeRoute;
    private java.util.List<Polyline> routePolylines = new java.util.ArrayList<>();
    private Marker destinationMarker;
    private LocationComponent locationComponent;

    // Throttling for navigation packets (Bug #2 fix)
    private long lastPacketSentTime = 0;
    private static final long PACKET_SEND_INTERVAL_MS = 1000; // 1 second
    private int lastDistance = -1;
    private int lastTurnIcon = -1;
    private boolean isNavigationStarted = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.d("NavigationActivity", "onCreate called");
        setContentView(R.layout.activity_navigation);

        originLat = getIntent().getDoubleExtra("origin_lat", 0);
        originLng = getIntent().getDoubleExtra("origin_lng", 0);
        destLat = getIntent().getDoubleExtra("dest_lat", 0);
        destLng = getIntent().getDoubleExtra("dest_lng", 0);

        android.util.Log.d("NavigationActivity", String.format("Destination: %f, %f", destLat, destLng));

        initUI(savedInstanceState);

        if (destLat == 0 || destLng == 0) {
            android.util.Log.e("NavigationActivity", "Invalid destination coordinates!");
            Toast.makeText(this, "Invalid destination", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        navigationSession = new com.suzuki.sconnect.utils.NavigationSession();
        setupLocationUpdates();
        setupNavigation();
    }

    private void initUI(Bundle savedInstanceState) {
        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);

        guidanceCard = findViewById(R.id.guidance_card);
        tvDistance = findViewById(R.id.tv_distance);
        tvInstruction = findViewById(R.id.tv_instruction);
        maneuverIcon = findViewById(R.id.maneuver_icon);

        findViewById(R.id.btn_stop_nav).setOnClickListener(v -> stopNavigation());
    }

    @Override
    public void onMapReady(MapplsMap mapplsMap) {
        this.mapplsMap = mapplsMap;
        android.util.Log.d("NavigationActivity", "Map is ready");

        // Wait for style to load before enabling location component
        mapplsMap.getStyle(style -> {
            if (style != null) {
                android.util.Log.d("NavigationActivity", "Map style loaded");
                enableLocationComponent();
            } else {
                android.util.Log.w("NavigationActivity", "Map style is null");
            }
        });

        // Initial camera position (center on destination area)
        if (destLat != 0 && destLng != 0) {
            mapplsMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(destLat, destLng), 14));
        }
    }

    private void enableLocationComponent() {
        if (mapplsMap == null) {
            android.util.Log.w("NavigationActivity", "Cannot enable location: map is null");
            return;
        }

        try {
            locationComponent = mapplsMap.getLocationComponent();
            if (locationComponent != null && mapplsMap.getStyle() != null) {
                LocationComponentActivationOptions options = LocationComponentActivationOptions
                        .builder(this, mapplsMap.getStyle())
                        .build();
                locationComponent.activateLocationComponent(options);
                locationComponent.setLocationComponentEnabled(true);
                locationComponent.setRenderMode(RenderMode.GPS);
                android.util.Log.d("NavigationActivity", "Location component enabled successfully");
            } else {
                android.util.Log.w("NavigationActivity", "Location component or style is null");
            }
        } catch (Exception e) {
            android.util.Log.e("NavigationActivity", "Error enabling location component", e);
        }
    }

    @Override
    public void onMapError(int code, String message) {
        android.util.Log.e("NavigationActivity", "Map Error (" + code + "): " + message);
    }

    private void setupLocationUpdates() {
        locationManager = (android.location.LocationManager) getSystemService(android.content.Context.LOCATION_SERVICE);
        locationListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(android.location.Location location) {
                android.util.Log.d("NavigationActivity",
                        "onLocationChanged: " + location.getLatitude() + ", " + location.getLongitude());

                // GPS tracking camera mode handles camera updates automatically
                // No need for manual camera animation

                if (navigationSession != null) {
                    navigationSession.onLocationChanged(location);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        try {
            if (androidx.core.app.ActivityCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 10,
                        locationListener);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void setupNavigation() {
        android.util.Log.d("NavigationActivity", String.format("setupNavigation: origin(%f, %f), dest(%f, %f)",
                originLat, originLng, destLat, destLng));

        DirectionPoint destination = DirectionPoint.setDirection(Point.fromLngLat(destLng, destLat), "Destination", "");

        DirectionOptions.Builder builder = DirectionOptions.builder()
                .showAlternative(true)
                .steps(true)
                .showStartNavigation(true)
                .profile(DirectionsCriteria.PROFILE_BIKING)
                .annotation(
                        Arrays.asList(DirectionsCriteria.ANNOTATION_CONGESTION, DirectionsCriteria.ANNOTATION_DURATION))
                .destination(destination);

        directionFragment = DirectionFragment.newInstance(builder.build());
        directionFragment.setDirectionCallback(this);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, directionFragment, DirectionFragment.class.getSimpleName())
                .commit();
    }

    @Override
    public void onCancel() {
        android.util.Log.d("NavigationActivity", "onCancel callback received from Mappls SDK");
        finish();
    }

    @Override
    public void onStartNavigation(DirectionPoint origin, DirectionPoint destination,
            java.util.List<DirectionPoint> waypoints, DirectionsResponse directionsResponse, int index) {

        android.util.Log.d("NavigationActivity", "onStartNavigation callback received! Index: " + index);
        isNavigationStarted = true;

        // Transition UI
        if (guidanceCard != null) {
            guidanceCard.setVisibility(android.view.View.VISIBLE);
        }

        // Hide the planning fragment's UI to clear the screen
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag(DirectionFragment.class.getSimpleName());
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().hide(fragment).commit();
        }

        Toast.makeText(this, "Starting Navigation & Cluster Sync...", Toast.LENGTH_SHORT).show();

        if (directionsResponse != null && directionsResponse.routes() != null
                && directionsResponse.routes().size() > index) {

            activeRoute = directionsResponse.routes().get(index);
            android.util.Log.d("NavigationActivity",
                    "Starting NavigationSession with route: " + activeRoute.distance() + "m");

            // Draw route on map with traffic colors
            drawRouteOnMap(activeRoute);

            // Add destination marker
            addDestinationMarker();

            // Enable GPS tracking camera (only if location component is active)
            if (locationComponent != null && locationComponent.isLocationComponentActivated()) {
                try {
                    locationComponent.setCameraMode(CameraMode.TRACKING_GPS);
                    android.util.Log.d("NavigationActivity", "GPS tracking camera enabled");
                } catch (Exception e) {
                    android.util.Log.e("NavigationActivity", "Error setting camera mode", e);
                }
            } else {
                android.util.Log.w("NavigationActivity", "Location component not activated, skipping camera tracking");
            }

            navigationSession.startSession(activeRoute, this);
        } else {
            android.util.Log.e("NavigationActivity", "onStartNavigation: Invalid route or response null");
        }
    }

    private void drawRouteOnMap(DirectionsRoute route) {
        if (mapplsMap == null || route == null)
            return;

        // Clear existing route polylines
        for (Polyline polyline : routePolylines) {
            mapplsMap.removePolyline(polyline);
        }
        routePolylines.clear();

        try {
            // Get route geometry
            String encodedPolyline = route.geometry();
            if (encodedPolyline == null || encodedPolyline.isEmpty()) {
                android.util.Log.w("NavigationActivity", "Route geometry is null or empty");
                return;
            }

            // Decode polyline to get coordinates
            java.util.List<LatLng> routePoints = decodePolyline(encodedPolyline);

            if (routePoints.isEmpty()) {
                android.util.Log.w("NavigationActivity", "No route points decoded");
                return;
            }

            // Draw entire route in blue
            PolylineOptions polylineOptions = new PolylineOptions()
                    .addAll(routePoints)
                    .color(android.graphics.Color.parseColor("#3B82F6"))
                    .width(8f);
            Polyline polyline = mapplsMap.addPolyline(polylineOptions);
            routePolylines.add(polyline);

            android.util.Log.d("NavigationActivity", "Route drawn in blue");
        } catch (Exception e) {
            android.util.Log.e("NavigationActivity", "Error drawing route", e);
        }
    }

    private java.util.List<LatLng> decodePolyline(String encoded) {
        java.util.List<LatLng> poly = new java.util.ArrayList<>();
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
                int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                lat += dlat;

                shift = 0;
                result = 0;
                do {
                    b = encoded.charAt(index++) - 63;
                    result |= (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20);
                int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                lng += dlng;

                // Use precision 6 (1E6) for Mappls polylines
                LatLng p = new LatLng((double) lat / 1E6, (double) lng / 1E6);
                poly.add(p);
            }
        } catch (Exception e) {
            android.util.Log.e("NavigationActivity", "Error decoding polyline", e);
        }

        return poly;
    }

    private void addDestinationMarker() {
        if (mapplsMap == null || destLat == 0 || destLng == 0)
            return;

        try {
            // Remove existing marker if any
            if (destinationMarker != null) {
                mapplsMap.removeMarker(destinationMarker);
            }

            // Add new destination marker
            MarkerOptions markerOptions = new MarkerOptions()
                    .position(new LatLng(destLat, destLng))
                    .title("Destination");
            destinationMarker = mapplsMap.addMarker(markerOptions);

            android.util.Log.d("NavigationActivity", "Destination marker added");
        } catch (Exception e) {
            android.util.Log.e("NavigationActivity", "Error adding destination marker", e);
        }
    }

    @Override
    public void onNavigationUpdate(int distanceMeters, int turnIconId, String instruction) {
        android.util.Log.d("NavigationActivity", String.format("onNavigationUpdate: %dm, icon: %d, inst: %s",
                distanceMeters, turnIconId, instruction));

        // Update UI Card
        runOnUiThread(() -> {
            if (tvDistance != null) {
                String distText = (distanceMeters < 1000) ? String.format("In %d m", distanceMeters)
                        : String.format("In %.1f km", distanceMeters / 1000.0);
                tvDistance.setText(distText);
            }
            if (tvInstruction != null)
                tvInstruction.setText(instruction);
        });

        // Throttle packet transmission to 1 per second
        long currentTime = System.currentTimeMillis();
        long timeSinceLastPacket = currentTime - lastPacketSentTime;

        boolean shouldSend = false;
        if (timeSinceLastPacket >= PACKET_SEND_INTERVAL_MS) {
            shouldSend = true;
        } else if (lastTurnIcon != turnIconId) {
            shouldSend = true;
        } else if (Math.abs(lastDistance - distanceMeters) > 50) {
            shouldSend = true;
        }

        if (!shouldSend)
            return;

        lastPacketSentTime = currentTime;
        lastDistance = distanceMeters;
        lastTurnIcon = turnIconId;

        android.content.SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        boolean usesInvertedChecksum = prefs.getBoolean("usesInvertedChecksum", false);

        byte[] packet = com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder.buildNavigationPacket(
                distanceMeters, turnIconId, instruction, usesInvertedChecksum);

        android.content.Intent intent = new android.content.Intent(BleConnectionService.ACTION_SEND_PACKET);
        intent.setPackage(getPackageName());
        intent.putExtra(BleConnectionService.EXTRA_PACKET, packet);

        Log.d("SuzukiBLE", "NavigationActivity: Sending broadcast intent: " + intent.getAction() + " to package: "
                + intent.getPackage());
        sendBroadcast(intent);
    }

    private void stopNavigation() {
        android.util.Log.d("NavigationActivity", "Stopping Navigation");
        isNavigationStarted = false;

        // Clear route visualization
        for (Polyline polyline : routePolylines) {
            if (mapplsMap != null) {
                mapplsMap.removePolyline(polyline);
            }
        }
        routePolylines.clear();

        // Remove destination marker
        if (destinationMarker != null && mapplsMap != null) {
            mapplsMap.removeMarker(destinationMarker);
            destinationMarker = null;
        }

        // Disable GPS tracking camera
        if (locationComponent != null) {
            locationComponent.setCameraMode(CameraMode.NONE);
        }

        // Hide guidance card
        if (guidanceCard != null)
            guidanceCard.setVisibility(android.view.View.GONE);

        // Stop navigation session
        if (navigationSession != null)
            navigationSession.stopSession();

        // Show planning fragment again
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
                .findFragmentByTag(DirectionFragment.class.getSimpleName());
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().show(fragment).commit();
        } else {
            finish();
        }
    }

    @Override
    public void onDestinationReached() {
        Toast.makeText(this, "Destination Reached!", Toast.LENGTH_LONG).show();
        stopNavigation();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapView != null)
            mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null)
            mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null)
            mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mapView != null)
            mapView.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null)
            mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null)
            mapView.onDestroy();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (navigationSession != null) {
            navigationSession.stopSession();
        }
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null)
            mapView.onSaveInstanceState(outState);
    }
}
