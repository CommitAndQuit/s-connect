package com.suzuki.sconnect.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.Style;
import com.mappls.sdk.maps.annotations.MarkerOptions;
import com.mappls.sdk.maps.camera.CameraPosition;
import com.mappls.sdk.maps.camera.CameraUpdateFactory;
import com.mappls.sdk.maps.geometry.LatLng;
import com.mappls.sdk.maps.location.LocationComponent;
import com.mappls.sdk.maps.location.LocationComponentActivationOptions;
import com.mappls.sdk.maps.location.modes.CameraMode;
import com.mappls.sdk.maps.location.modes.RenderMode;
import com.mappls.sdk.services.api.directions.MapplsDirectionManager;
import com.mappls.sdk.services.api.directions.MapplsDirections;
import com.mappls.sdk.services.api.directions.DirectionsCriteria;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;
import com.mappls.sdk.services.api.directions.models.DirectionsRoute;
import com.mappls.sdk.maps.annotations.Polyline;
import com.mappls.sdk.maps.annotations.PolylineOptions;
import com.suzuki.sconnect.R;
import com.suzuki.sconnect.utils.DebugLogger;

import java.util.ArrayList;
import java.util.List;

import com.mappls.sdk.services.api.OnResponseCallback;

/**
 * P1 — Last Parked Location
 *
 * Shows the location where the bike was last parked (saved to SharedPrefs on BLE disconnect)
 * on a Mappls map. Draws a route from the user's current location to the parked spot:
 *   - Walking route if distance < 500m (original app behaviour)
 *   - Biking route if distance >= 500m
 *
 * Also provides a share button to share the parked location via Android share intent.
 */
public class LastParkedLocationActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "LastParkedLocation";

    private MapView mapView;
    private MapplsMap mapplsMap;
    private LocationComponent locationComponent;

    private LinearLayout layoutNoGps;
    private LinearLayout layoutNoInternet;
    private LinearLayout panelNavigation;
    private TextView tvDistance;
    private TextView tvParkedTime;
    private Button btnNavigate;
    private Button btnShare;

    // Parked location (loaded from SharedPrefs)
    private double parkedLat = 0.0;
    private double parkedLng = 0.0;
    private long parkedTimeMs = 0L;

    // Current user location (from LocationManager)
    private double currentLat = 0.0;
    private double currentLng = 0.0;

    private List<Polyline> routePolylines = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_last_parked_location);

        initViews();
        loadParkedLocation();
        checkConnectivity();
    }

    private void initViews() {
        Toolbar toolbar = findViewById(R.id.toolbar_parked);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        mapView = findViewById(R.id.map_view_parked);
        layoutNoGps = findViewById(R.id.layout_no_gps);
        layoutNoInternet = findViewById(R.id.layout_no_internet);
        panelNavigation = findViewById(R.id.panel_navigation);
        tvDistance = findViewById(R.id.tv_parked_distance);
        tvParkedTime = findViewById(R.id.tv_parked_time);
        btnNavigate = findViewById(R.id.btn_navigate_to_parked);
        btnShare = findViewById(R.id.btn_share_parked);

        btnNavigate.setOnClickListener(v -> openInMaps());
        btnShare.setOnClickListener(v -> shareLocation());

        Button btnRetryInternet = findViewById(R.id.btn_retry_internet);
        btnRetryInternet.setOnClickListener(v -> checkConnectivity());

        mapView.onCreate(null);
    }

    /**
     * Load the last parked location from SharedPreferences.
     * Shows the "no GPS" placeholder if no location was ever saved.
     */
    private void loadParkedLocation() {
        SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        parkedLat = prefs.getFloat("last_parked_lat", 0.0f);
        parkedLng = prefs.getFloat("last_parked_lng", 0.0f);
        parkedTimeMs = prefs.getLong("last_parked_time", 0L);

        if (parkedLat == 0.0 && parkedLng == 0.0) {
            // No parked location saved yet
            layoutNoGps.setVisibility(View.VISIBLE);
            panelNavigation.setVisibility(View.GONE);
            DebugLogger.w(TAG, "No parked location found in SharedPrefs");
            return;
        }

        layoutNoGps.setVisibility(View.GONE);

        // Show elapsed time since parking
        if (parkedTimeMs > 0) {
            CharSequence timeAgo = DateUtils.getRelativeTimeSpanString(
                    parkedTimeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            tvParkedTime.setText(timeAgo);
        }

        // Try to get current location for distance computation
        loadCurrentLocation();

        // Start map once we know the parked location is valid
        mapView.getMapAsync(this);
    }

    private void loadCurrentLocation() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location location = null;

            if (androidx.core.app.ActivityCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {

                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            }

            if (location != null) {
                currentLat = location.getLatitude();
                currentLng = location.getLongitude();
                DebugLogger.d(TAG, String.format("Current location: %.5f, %.5f", currentLat, currentLng));
            }
        } catch (Exception e) {
            DebugLogger.e(TAG, "Error fetching current location", e);
        }
    }

    private void checkConnectivity() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo active = cm.getActiveNetworkInfo();
        boolean connected = active != null && active.isConnectedOrConnecting();

        if (!connected) {
            layoutNoInternet.setVisibility(View.VISIBLE);
            panelNavigation.setVisibility(View.GONE);
        } else {
            layoutNoInternet.setVisibility(View.GONE);
            if (parkedLat != 0.0 || parkedLng != 0.0) {
                panelNavigation.setVisibility(View.VISIBLE);
            }
        }
    }

    // ── Mappls Map Callbacks ──────────────────────────────────────────────────────────────

    @Override
    public void onMapReady(@NonNull MapplsMap mapplsMap) {
        this.mapplsMap = mapplsMap;

        mapplsMap.getStyle(style -> {
            if (style != null) enableLocationComponent(style);
        });

        // Centre map on the parked location
        LatLng parkedLatLng = new LatLng(parkedLat, parkedLng);
        mapplsMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder()
                        .target(parkedLatLng)
                        .zoom(16.0)
                        .build()));

        // Drop a pin at the parked location
        mapplsMap.addMarker(new MarkerOptions()
                .position(parkedLatLng)
                .title("Parked Here"));

        // Fetch route from current location to parked spot
        if (currentLat != 0.0 && currentLng != 0.0) {
            fetchRouteToParkedLocation();
        } else {
            tvDistance.setText("Current location unavailable");
        }
    }

    @Override
    public void onMapError(int errorCode, String errorMessage) {
        Log.e(TAG, "Map error (" + errorCode + "): " + errorMessage);
    }

    private void enableLocationComponent(Style style) {
        try {
            locationComponent = mapplsMap.getLocationComponent();
            if (locationComponent != null) {
                locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(this, style).build());
                locationComponent.setLocationComponentEnabled(true);
                locationComponent.setCameraMode(CameraMode.NONE);
                locationComponent.setRenderMode(RenderMode.COMPASS);
            }
        } catch (Exception e) {
            DebugLogger.e(TAG, "Error enabling location component", e);
        }
    }

    // ── Route Fetching ────────────────────────────────────────────────────────────────────

    /**
     * Fetch walking or biking route to the parked location.
     * Official app uses walking if distance < 500m, biking otherwise.
     */
    private void fetchRouteToParkedLocation() {
        LatLng currentLatLng = new LatLng(currentLat, currentLng);
        LatLng parkedLatLng = new LatLng(parkedLat, parkedLng);

        // Compute straight-line distance in metres
        float[] distResult = new float[1];
        android.location.Location.distanceBetween(currentLat, currentLng, parkedLat, parkedLng, distResult);
        float distanceMeters = distResult[0];

        String distText = distanceMeters < 1000
                ? String.format("%.0f m away", distanceMeters)
                : String.format("%.1f km away", distanceMeters / 1000f);
        tvDistance.setText(distText);

        // Use walking profile for short distances (< 500m), biking otherwise
        boolean useWalking = distanceMeters < 500f;
        String profile = useWalking
                ? DirectionsCriteria.PROFILE_WALKING
                : DirectionsCriteria.PROFILE_BIKING;

        DebugLogger.i(TAG, String.format("Fetching %s route to parked location (%.0fm)",
                useWalking ? "walking" : "biking", distanceMeters));

        MapplsDirections directions = MapplsDirections.builder()
                .origin(Point.fromLngLat(currentLng, currentLat))
                .destination(Point.fromLngLat(parkedLng, parkedLat))
                .steps(Boolean.TRUE)
                .profile(profile)
                .overview("full")
                .build();

        MapplsDirectionManager.newInstance(directions).call(new OnResponseCallback<DirectionsResponse>() {
            @Override
            public void onSuccess(DirectionsResponse response) {
                if (response != null && response.routes() != null && !response.routes().isEmpty()) {
                    DirectionsRoute route = response.routes().get(0);
                    drawRouteOnMap(route, currentLatLng, parkedLatLng);
                } else {
                    DebugLogger.w(TAG, "Route response empty or null");
                }
            }

            @Override
            public void onError(int code, String message) {
                DebugLogger.e(TAG, "Route fetch failed: " + code + " " + message, null);
                runOnUiThread(() -> Toast.makeText(LastParkedLocationActivity.this,
                        "Could not fetch route", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void drawRouteOnMap(DirectionsRoute route, LatLng origin, LatLng destination) {
        if (mapplsMap == null || route == null || route.geometry() == null) return;

        runOnUiThread(() -> {
            // Clear existing polylines
            for (Polyline p : routePolylines) mapplsMap.removePolyline(p);
            routePolylines.clear();

            // Decode and draw polyline
            List<LatLng> points = decodePolyline(route.geometry());
            if (!points.isEmpty()) {
                Polyline polyline = mapplsMap.addPolyline(new PolylineOptions()
                        .addAll(points)
                        .color(android.graphics.Color.parseColor("#6C63FF"))
                        .width(6f));
                routePolylines.add(polyline);
            }

            // Zoom to fit both markers
            try {
                com.mappls.sdk.maps.camera.CameraUpdate bounds =
                        CameraUpdateFactory.newLatLngBounds(
                                new com.mappls.sdk.maps.geometry.LatLngBounds.Builder()
                                        .include(origin)
                                        .include(destination)
                                        .build(),
                                80);
                mapplsMap.animateCamera(bounds, 1000);
            } catch (Exception e) {
                DebugLogger.e(TAG, "Error fitting camera to route bounds", e);
            }
        });
    }

    /** Standard polyline decoder (precision 6). */
    private List<LatLng> decodePolyline(String encoded) {
        List<LatLng> poly = new ArrayList<>();
        int index = 0, len = encoded.length(), lat = 0, lng = 0;
        try {
            while (index < len) {
                int b, shift = 0, result = 0;
                do { b = encoded.charAt(index++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
                lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                shift = 0; result = 0;
                do { b = encoded.charAt(index++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
                lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
                poly.add(new LatLng(lat / 1E6, lng / 1E6));
            }
        } catch (Exception e) { DebugLogger.e(TAG, "Polyline decode error", e); }
        return poly;
    }

    // ── Action Handlers ───────────────────────────────────────────────────────────────────

    /** Open parked location in Google Maps for turn-by-turn navigation. */
    private void openInMaps() {
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    String.format("google.navigation:q=%f,%f", parkedLat, parkedLng));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, uri);
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                // Fallback: open browser maps
                android.net.Uri browserUri = android.net.Uri.parse(
                        String.format("https://maps.google.com/?q=%f,%f", parkedLat, parkedLng));
                startActivity(new Intent(Intent.ACTION_VIEW, browserUri));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not open maps", Toast.LENGTH_SHORT).show();
        }
    }

    /** Share the parked location via Android share sheet. */
    private void shareLocation() {
        String mapsLink = String.format(
                "https://maps.google.com/?q=%f,%f", parkedLat, parkedLng);
        String shareText = "My bike is parked here: " + mapsLink;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share Parked Location"));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────

    @Override protected void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override protected void onPause() { super.onPause(); if (mapView != null) mapView.onPause(); }
    @Override protected void onStop() { super.onStop(); if (mapView != null) mapView.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) mapView.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }
}
