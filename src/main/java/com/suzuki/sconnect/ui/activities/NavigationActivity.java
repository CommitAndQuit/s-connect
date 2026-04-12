package com.suzuki.sconnect.ui.activities;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;
import com.suzuki.sconnect.data.model.TripRecord;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
import com.mappls.sdk.services.api.directions.models.LegStep;
import com.mappls.sdk.services.api.directions.models.RouteLeg;

import java.util.Arrays;
import java.util.UUID;

import io.realm.Realm;

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

    // Throttling for navigation packets
    private final android.os.Handler clusterHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int lastDistance = -1;
    private int lastTurnIcon = -1;
    private String lastEtaStr = "1200PM";
    private boolean isNavigationStarted = false;
    private boolean isRerouting = false;
    private com.suzuki.sconnect.utils.MapplsRouteManager routeManager;

    // Bug #1 fix: flag set when startActualNavigation runs before locationComponent
    // is ready
    private boolean pendingTrackingMode = false;

    // Bug #2 fix: cache decoded route points so we don't re-decode on every GPS
    // tick
    private java.util.List<LatLng> cachedRoutePoints = null;

    private static final int REROUTE_THRESHOLD_METERS = 75; // raised from 50 to absorb GPS jitter
    private static final long CLUSTER_INTERVAL_MS = 200;

    // ── P2: Trip Recording State ───────────────────────────────────────────────────
    private Realm realm;
    private String currentTripId = null;  // Realm primary key of the active trip
    private int tripStartOdometer = -1;   // ODO snapshot at trip start
    private int tripTopSpeedKmh = 0;      // Running max speed during this trip
    private double tripTotalSpeedSum = 0; // For average speed computation
    private int tripSpeedSamples = 0;
    private float tripFuelAtStart = 0;    // Cumulative mileage from service at trip start

    /** Receives ACTION_VEHICLE_DATA to capture speed for trip stats. */
    private final BroadcastReceiver vehicleDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            int speed = intent.getIntExtra("speed", 0);
            int odometer = intent.getIntExtra("odometer", -1);

            // Update top speed
            if (speed > tripTopSpeedKmh) tripTopSpeedKmh = speed;

            // Running average speed (only count non-zero samples while moving)
            if (speed > 0) {
                tripTotalSpeedSum += speed;
                tripSpeedSamples++;
            }

            // Update trip distance in Realm (live update)
            if (tripStartOdometer >= 0 && odometer > tripStartOdometer && currentTripId != null) {
                float distKm = odometer - tripStartOdometer;
                realm.executeTransactionAsync(r -> {
                    TripRecord t = r.where(TripRecord.class)
                            .equalTo("id", currentTripId).findFirst();
                    if (t != null) t.setDistanceKm(distKm);
                });
            }
        }
    };
    // ─────────────────────────────────────────────────────────────────────

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
        routeManager = new com.suzuki.sconnect.utils.MapplsRouteManager();
        setupLocationUpdates();

        // P2: Initialise Realm for trip recording
        realm = Realm.getDefaultInstance();

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

        findViewById(R.id.btn_stop_nav).setOnClickListener(v -> stopNavigation());

        // Bug #1 fix: re-center FAB snaps camera back to current location
        findViewById(R.id.btn_recenter).setOnClickListener(v -> recenterCamera());
    }

    /** Snaps the camera back to the user's current location. */
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
                        .useDefaultLocationEngine(true)
                        .build();
                locationComponent.activateLocationComponent(options);
                locationComponent.setLocationComponentEnabled(true);
                locationComponent.setRenderMode(RenderMode.GPS); // Bearing-aware puck
                android.util.Log.d("NavigationActivity", "Location component enabled successfully");

                // Bug #1 fix: if startActualNavigation() already ran but locationComponent
                // was null at that time, apply the deferred camera tracking mode now.
                if (pendingTrackingMode) {
                    pendingTrackingMode = false;
                    applyTrackingCameraMode();
                }
            } else {
                android.util.Log.w("NavigationActivity", "Location component or style is null");
            }
        } catch (Exception e) {
            android.util.Log.e("NavigationActivity", "Error enabling location component", e);
        }
    }

    /** Applies TRACKING_GPS camera mode with navigation tilt & zoom. */
    private void applyTrackingCameraMode() {
        if (locationComponent == null || !locationComponent.isLocationComponentActivated())
            return;
        locationComponent.setCameraMode(CameraMode.TRACKING_GPS);
        int topPadding = (int) (mapView.getHeight() * 0.65);
        mapplsMap.setPadding(0, topPadding, 0, 0);
        mapplsMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder(mapplsMap.getCameraPosition())
                        .tilt(45)
                        .zoom(18.5)
                        .build()));
        android.util.Log.d("NavigationActivity", "Camera TRACKING_GPS mode applied");
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

                // CameraMode.TRACKING_GPS moves the camera automatically once set.

                if (navigationSession != null) {
                    navigationSession.onLocationChanged(location);
                }

                // Bug #2 fix: guard only on activeRoute (not isNavigationStarted flag)
                // so rerouting fires regardless of which flow started navigation.
                if (activeRoute != null && !isRerouting) {
                    checkForOffRoute(location);
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
        startActualNavigation(directionsResponse.routes().get(index));
    }

    private void setupNavigationFromSelection(int index) {
        // For simplicity, we trigger a fetch or assume a static/pre-fetched route
        // In a real app, RouteSelectionActivity would pass the route data via JSON or
        // shared repo
        // Here we'll just fetch again to get the full route object
        Point destination = Point.fromLngLat(destLng, destLat);
        Point origin = Point.fromLngLat(originLng != 0 ? originLng : 77.2090, originLat != 0 ? originLat : 28.6139);

        routeManager.fetchRoute(origin, destination, new com.suzuki.sconnect.utils.MapplsRouteManager.RouteCallback() {
            @Override
            public void onRouteSuccess(DirectionsResponse response) {
                runOnUiThread(() -> startActualNavigation(response.routes().get(index)));
            }

            @Override
            public void onRouteStringError(String error) {
                /* handle error */ }
        });
    }

    private void startActualNavigation(DirectionsRoute route) {
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

        if (route != null) {

            activeRoute = route;
            android.util.Log.d("NavigationActivity",
                    "Starting NavigationSession with route: " + activeRoute.distance() + "m");

            drawRouteOnMap(activeRoute);
            addDestinationMarker();

            // Bug #1 fix: apply camera tracking mode, or defer if locationComponent
            // is not yet ready (map style may still be loading).
            if (locationComponent != null && locationComponent.isLocationComponentActivated()) {
                applyTrackingCameraMode();
            } else {
                pendingTrackingMode = true; // will be applied in enableLocationComponent()
                android.util.Log.d("NavigationActivity",
                        "locationComponent not ready yet — deferring TRACKING_GPS mode");
            }

            navigationSession.startSession(activeRoute, this);

            // Start Cluster Heartbeat loop
            clusterHandler.post(clusterRunnable);

            // Send ?6 Identification Packet (once)
            sendIdentificationPacket();

            // ── P2: Begin trip recording ───────────────────────────────────────
            startTripRecording();
            // ────────────────────────────────────────────────────────────

        } else {
            android.util.Log.e("NavigationActivity", "startActualNavigation: route null");
        }
    }

    private void sendIdentificationPacket() {
        android.content.SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        boolean usesInvertedChecksum = prefs.getBoolean("usesInvertedChecksum", false);
        String userName = prefs.getString("user_name", "USER");

        byte[] packet = com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder.buildIdentificationPacket(
                userName, false, usesInvertedChecksum);

        sendPacketBroadcast(packet);
    }

    private final Runnable clusterRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isNavigationStarted)
                return;

            android.content.SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
            boolean usesInvertedChecksum = prefs.getBoolean("usesInvertedChecksum", false);

            // Bug #5: Airplane mode check (overrides all except status 0 logic in original)
            boolean airplaneMode = android.provider.Settings.System.getInt(
                    getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1;

            boolean hasGps = locationManager != null
                    && locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);

            // Priority: Airplane (0) > Reroute (2) > GPS Lost (4) > Normal (1)
            String statusCode;
            if (airplaneMode) {
                statusCode = "0";
            } else if (isRerouting) {
                statusCode = "2";
            } else if (!hasGps) {
                statusCode = "4";
            } else {
                statusCode = "1";
            }

            byte[] packet = com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder.buildNavigationPacket(
                    lastDistance != -1 ? lastDistance : 0,
                    lastTurnIcon != -1 ? lastTurnIcon : 46,
                    lastEtaStr,
                    statusCode,
                    usesInvertedChecksum);

            sendPacketBroadcast(packet);
            clusterHandler.postDelayed(this, CLUSTER_INTERVAL_MS);
        }
    };

    private void sendPacketBroadcast(byte[] packet) {
        android.content.Intent intent = new android.content.Intent(BleConnectionService.ACTION_SEND_PACKET);
        intent.setPackage(getPackageName());
        intent.putExtra(BleConnectionService.EXTRA_PACKET, packet);
        sendBroadcast(intent);
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

            // Decode polyline to get coordinates and CACHE for off-route checks.
            // Bug #2 fix: this avoids re-decoding thousands of points on every GPS tick.
            java.util.List<LatLng> routePoints = decodePolyline(encodedPolyline);
            cachedRoutePoints = routePoints; // store for checkForOffRoute()

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

            android.util.Log.d("NavigationActivity", "Route drawn in blue with " + routePoints.size() + " points");
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
    public void onNavigationUpdate(int distanceMeters, int turnIconId, String instruction, String etaStr) {
        android.util.Log.d("NavigationActivity", String.format("onNavigationUpdate: %dm, icon: %d, inst: %s, ETA: %s",
                distanceMeters, turnIconId, instruction, etaStr));

        lastDistance = distanceMeters;
        lastTurnIcon = turnIconId;
        lastEtaStr = etaStr;

        runOnUiThread(() -> {
            if (tvDistance != null) {
                String distText = (distanceMeters < 1000) ? String.format("In %d m", distanceMeters)
                        : String.format("In %.1f km", distanceMeters / 1000.0);
                tvDistance.setText(distText);
            }
            if (tvInstruction != null)
                tvInstruction.setText(instruction);
            if (maneuverIcon != null) {
                // We'd ideally map turnIconId to a local drawable here
            }
        });
    }

    private void checkForOffRoute(android.location.Location location) {
        if (activeRoute == null || location == null)
            return;

        // Bug #2 fix: use the cached route points instead of re-decoding on every tick.
        java.util.List<LatLng> points = cachedRoutePoints;
        if (points == null || points.isEmpty())
            return;

        double minDistance = Double.MAX_VALUE;
        for (LatLng p : points) {
            float[] results = new float[1];
            android.location.Location.distanceBetween(
                    location.getLatitude(), location.getLongitude(),
                    p.getLatitude(), p.getLongitude(), results);
            if (results[0] < minDistance)
                minDistance = results[0];
            // Early-exit: no need to check further if already well within threshold
            if (minDistance < REROUTE_THRESHOLD_METERS / 2.0)
                break;
        }

        android.util.Log.d("NavigationActivity", String.format("Off-route check: %.1fm from route", minDistance));
        if (minDistance > REROUTE_THRESHOLD_METERS) {
            triggerReroute(location);
        }
    }

    private void triggerReroute(android.location.Location location) {
        if (isRerouting)
            return;
        isRerouting = true;
        Log.i("NavigationActivity", "Off route detected! Rerouting...");

        Point origin = Point.fromLngLat(location.getLongitude(), location.getLatitude());
        Point destination = Point.fromLngLat(destLng, destLat);

        routeManager.fetchRoute(origin, destination, new com.suzuki.sconnect.utils.MapplsRouteManager.RouteCallback() {
            @Override
            public void onRouteSuccess(DirectionsResponse response) {
                runOnUiThread(() -> {
                    activeRoute = response.routes().get(0);
                    drawRouteOnMap(activeRoute);
                    navigationSession.startSession(activeRoute, NavigationActivity.this);
                    isRerouting = false;
                    Log.i("NavigationActivity", "Reroute successful.");
                });
            }

            @Override
            public void onRouteStringError(String error) {
                isRerouting = false;
                Log.e("NavigationActivity", "Reroute failed: " + error);
            }
        });
    }

    // ── P2: Trip Recording Helpers ────────────────────────────────────────────────────────

    /**
     * Create a new IN_PROGRESS TripRecord in Realm and register the vehicle data receiver
     * so this Activity can capture speed/ODO stats while navigating.
     */
    private void startTripRecording() {
        tripTopSpeedKmh = 0;
        tripTotalSpeedSum = 0;
        tripSpeedSamples = 0;
        tripStartOdometer = -1;

        currentTripId = UUID.randomUUID().toString();

        String destination = getIntent().getStringExtra("destination_name");
        if (destination == null) destination = "Destination";
        final String destName = destination;

        String origin = getIntent().getStringExtra("origin_name");
        if (origin == null) origin = "Start";
        final String originName = origin;

        final String tripId = currentTripId;
        final long now = System.currentTimeMillis();

        realm.executeTransactionAsync(r -> {
            TripRecord trip = r.createObject(TripRecord.class, tripId);
            trip.setStartTimeMs(now);
            trip.setStartPlaceName(originName);
            trip.setEndPlaceName(destName);
            trip.setStatus("IN_PROGRESS");
        }, () -> Log.d("NavigationActivity", "P2: Trip record created: " + tripId),
           err -> Log.e("NavigationActivity", "P2: Realm error creating trip", err));

        // Register vehicle data receiver to capture speed and ODO during the trip
        IntentFilter filter = new IntentFilter(BleConnectionService.ACTION_VEHICLE_DATA);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vehicleDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vehicleDataReceiver, filter);
        }

        Log.i("NavigationActivity", "P2: Trip recording started: " + tripId);
    }

    /**
     * Finalize the current trip: set COMPLETED status, end time, and final stats.
     * @param destinationReached true if the user actually arrived; false if manually stopped.
     */
    private void finalizeTripRecording(boolean destinationReached) {
        if (currentTripId == null) return;

        final String tripId = currentTripId;
        currentTripId = null;

        final long endTime = System.currentTimeMillis();
        final int topSpeed = tripTopSpeedKmh;
        final float avgSpeed = tripSpeedSamples > 0
                ? (float) (tripTotalSpeedSum / tripSpeedSamples)
                : 0f;

        realm.executeTransactionAsync(r -> {
            TripRecord trip = r.where(TripRecord.class).equalTo("id", tripId).findFirst();
            if (trip != null) {
                trip.setEndTimeMs(endTime);
                trip.setTopSpeedKmh(topSpeed);
                trip.setAvgSpeedKmh(avgSpeed);
                trip.setStatus("COMPLETED");
            }
        }, () -> Log.i("NavigationActivity", "P2: Trip finalized: " + tripId +
                " | topSpeed=" + topSpeed + " avgSpeed=" + avgSpeed),
           err -> Log.e("NavigationActivity", "P2: Realm error finalizing trip", err));

        // Unregister vehicle data receiver
        try { unregisterReceiver(vehicleDataReceiver); } catch (Exception ignored) {}

        Log.i("NavigationActivity", "P2: Trip recording ended" + (destinationReached ? " (destination reached)" : " (manual stop)"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    private void stopNavigation() {
        android.util.Log.d("NavigationActivity", "Stopping Navigation");
        isNavigationStarted = false;
        clusterHandler.removeCallbacks(clusterRunnable);

        // P2: Finalize trip record (manual stop)
        finalizeTripRecording(false);

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
        finalizeTripRecording(true); // P2
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
        // P2: Unregister vehicle data receiver and clean up Realm
        try { unregisterReceiver(vehicleDataReceiver); } catch (Exception ignored) {}
        if (realm != null && !realm.isClosed()) realm.close();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null)
            mapView.onSaveInstanceState(outState);
    }
}
