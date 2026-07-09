package com.suzuki.sconnect.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.maps.geometry.LatLng;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;
import com.mappls.sdk.services.api.directions.models.DirectionsRoute;
import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;
import com.suzuki.sconnect.ble.protocol.VehicleProtocol;
import com.suzuki.sconnect.ble.protocol.VehicleProtocolFactory;
import com.suzuki.sconnect.data.model.TripRecord;
import com.suzuki.sconnect.ui.activities.NavigationActivity;
import com.suzuki.sconnect.utils.MapplsRouteManager;
import com.suzuki.sconnect.utils.NavigationSession;
import com.suzuki.sconnect.utils.NavigationStateHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.realm.Realm;

public class NavigationService extends Service implements NavigationSession.NavigationUpdateListener {

    private static final String TAG = "NavigationService";
    private static final String CHANNEL_ID = "NavigationServiceChannel";
    private static final int NOTIFICATION_ID = 101;

    public static final String ACTION_NAV_UPDATE = "com.suzuki.sconnect.ACTION_NAV_UPDATE";
    public static final String ACTION_NAV_ARRIVED = "com.suzuki.sconnect.ACTION_NAV_ARRIVED";
    public static final String ACTION_NAV_REROUTING = "com.suzuki.sconnect.ACTION_NAV_REROUTING";
    public static final String ACTION_NAV_NEW_ROUTE = "com.suzuki.sconnect.ACTION_NAV_NEW_ROUTE";

    // Broadcast Extras
    public static final String EXTRA_DISTANCE = "distance";
    public static final String EXTRA_TURN_ICON = "turnIcon";
    public static final String EXTRA_INSTRUCTION = "instruction";
    public static final String EXTRA_ETA = "eta";

    private LocationManager locationManager;
    private LocationListener locationListener;
    private NavigationSession navigationSession;
    private MapplsRouteManager routeManager;
    private DirectionsRoute activeRoute;

    // Route off-route detection
    private List<LatLng> cachedRoutePoints = null;
    private boolean isRerouting = false;
    private static final int REROUTE_THRESHOLD_METERS = 75;

    // Cluster Packet state
    private final Handler clusterHandler = new Handler(Looper.getMainLooper());
    private static final long CLUSTER_INTERVAL_MS = 200;
    private boolean isNavigationStarted = false;
    private int lastDistance = -1;
    private int lastTotalDistance = -1;
    private int lastTurnIcon = -1;
    private String lastEtaStr = "1200PM";
    private String lastInstruction = "Drive safe";

    // Trip Recording
    private Realm realm;
    private String currentTripId = null;
    private int tripStartOdometer = -1;
    private int tripTopSpeedKmh = 0;
    private double tripTotalSpeedSum = 0;
    private int tripSpeedSamples = 0;

    private NotificationManager notificationManager;

    private final BroadcastReceiver vehicleDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int speed = intent.getIntExtra("speed", 0);
            int odometer = intent.getIntExtra("odometer", -1);

            if (speed > tripTopSpeedKmh) tripTopSpeedKmh = speed;

            if (speed > 0) {
                tripTotalSpeedSum += speed;
                tripSpeedSamples++;
            }

            if (tripStartOdometer == -1 && odometer > 0) {
                tripStartOdometer = odometer;
            }

            if (tripStartOdometer >= 0 && odometer > tripStartOdometer && currentTripId != null) {
                float distKm = odometer - tripStartOdometer;
                realm.executeTransactionAsync(r -> {
                    TripRecord t = r.where(TripRecord.class).equalTo("id", currentTripId).findFirst();
                    if (t != null) t.setDistanceKm(distKm);
                });
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "NavigationService created");
        createNotificationChannel();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        realm = Realm.getDefaultInstance();
        navigationSession = new NavigationSession();
        routeManager = new MapplsRouteManager();

        IntentFilter filter = new IntentFilter(BleConnectionService.ACTION_VEHICLE_DATA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vehicleDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vehicleDataReceiver, filter);
        }

        setupLocationUpdates();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_NAVIGATION".equals(intent.getAction())) {
            finalizeTripRecording(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        activeRoute = NavigationStateHolder.getInstance().getCurrentRoute();

        if (activeRoute == null) {
            Log.e(TAG, "No route found in NavigationStateHolder! Stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting Navigation..."));
        startNavigation(activeRoute);

        return START_STICKY;
    }

    private void startNavigation(DirectionsRoute route) {
        isNavigationStarted = true;
        cacheRoutePoints(route);
        navigationSession.startSession(route, this);

        clusterHandler.post(clusterRunnable);
        sendIdentificationPacket();

        String origin = NavigationStateHolder.getInstance().getOriginName();
        String destination = NavigationStateHolder.getInstance().getDestinationName();
        startTripRecording(origin, destination);
        
        Intent brIntent = new Intent(ACTION_NAV_NEW_ROUTE);
        sendBroadcast(brIntent);
    }

    private void setupLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (navigationSession != null) {
                    navigationSession.onLocationChanged(location);
                }
                if (activeRoute != null && !isRerouting) {
                    checkForOffRoute(location);
                }
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 10, locationListener);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
        }
    }

    private void cacheRoutePoints(DirectionsRoute route) {
        String encoded = route.geometry();
        if (encoded != null && !encoded.isEmpty()) {
            cachedRoutePoints = decodePolyline(encoded);
        } else {
            cachedRoutePoints = new ArrayList<>();
        }
    }

    private void checkForOffRoute(Location location) {
        if (cachedRoutePoints == null || cachedRoutePoints.isEmpty()) return;

        double minDist = Double.MAX_VALUE;
        for (LatLng p : cachedRoutePoints) {
            float[] results = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), p.getLatitude(), p.getLongitude(), results);
            if (results[0] < minDist) minDist = results[0];
            if (minDist < REROUTE_THRESHOLD_METERS / 2.0) break;
        }

        if (minDist > REROUTE_THRESHOLD_METERS) {
            triggerReroute(location);
        }
    }

    private void triggerReroute(Location location) {
        if (isRerouting) return;
        isRerouting = true;
        Log.i(TAG, "Off route detected! Rerouting...");
        
        Intent intent = new Intent(ACTION_NAV_REROUTING);
        sendBroadcast(intent);

        Point origin = Point.fromLngLat(location.getLongitude(), location.getLatitude());
        Point destPoint = getDestinationPoint(activeRoute);
        if (destPoint == null) {
            isRerouting = false;
            return;
        }

        routeManager.fetchRoute(origin, destPoint, new MapplsRouteManager.RouteCallback() {
            @Override
            public void onRouteSuccess(DirectionsResponse response) {
                activeRoute = response.routes().get(0);
                NavigationStateHolder.getInstance().setCurrentRoute(activeRoute);
                cacheRoutePoints(activeRoute);
                navigationSession.startSession(activeRoute, NavigationService.this);
                isRerouting = false;
                Log.i(TAG, "Reroute successful.");

                Intent brIntent = new Intent(ACTION_NAV_NEW_ROUTE);
                sendBroadcast(brIntent);
            }

            @Override
            public void onRouteStringError(String error) {
                isRerouting = false;
                Log.e(TAG, "Reroute failed: " + error);
            }
        });
    }

    private Point getDestinationPoint(DirectionsRoute route) {
        if (route == null || route.legs() == null || route.legs().isEmpty()) return null;
        if (route.legs().get(0).steps() == null || route.legs().get(0).steps().isEmpty()) return null;
        int lastIndex = route.legs().get(0).steps().size() - 1;
        return route.legs().get(0).steps().get(lastIndex).maneuver().location();
    }

    @Override
    public void onNavigationUpdate(int distanceMeters, int totalDistanceRemaining, int turnIconId, String instruction, String etaStr) {
        lastDistance = distanceMeters;
        lastTotalDistance = totalDistanceRemaining;
        lastTurnIcon = turnIconId;
        lastInstruction = instruction;
        lastEtaStr = etaStr;

        NavigationStateHolder.getInstance().setNavigationUpdate(distanceMeters, turnIconId, instruction);

        Intent intent = new Intent(ACTION_NAV_UPDATE);
        intent.putExtra(EXTRA_DISTANCE, distanceMeters);
        intent.putExtra(EXTRA_TURN_ICON, turnIconId);
        intent.putExtra(EXTRA_INSTRUCTION, instruction);
        intent.putExtra(EXTRA_ETA, etaStr);
        sendBroadcast(intent);

        updateNotification(instruction, distanceMeters);
    }

    @Override
    public void onDestinationReached() {
        Log.d(TAG, "Destination reached!");
        finalizeTripRecording(true);
        Intent intent = new Intent(ACTION_NAV_ARRIVED);
        sendBroadcast(intent);
        stopSelf();
    }

    private final Runnable clusterRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isNavigationStarted) return;

            SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
            boolean usesImg = prefs.getBoolean("usesInvertedChecksum", false);

            boolean airplaneMode = Settings.System.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            boolean hasGps = locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

            String statusCode;
            if (airplaneMode) statusCode = "0";
            else if (isRerouting) statusCode = "2";
            else if (!hasGps) statusCode = "4";
            else statusCode = "1";

            SharedPreferences prefsObj = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
            String brandName = prefsObj.getString("brand_name", "suzuki");
            VehicleProtocol protocol = VehicleProtocolFactory.getProtocol(brandName);
            byte[] packet = protocol.buildNavigationPacket(
                    lastDistance != -1 ? lastDistance : 0,
                    lastTotalDistance != -1 ? lastTotalDistance : 0,
                    lastTurnIcon != -1 ? lastTurnIcon : 46,
                    lastEtaStr, statusCode, usesImg);

            Intent intent = new Intent(BleConnectionService.ACTION_SEND_PACKET);
            intent.setPackage(getPackageName());
            intent.putExtra(BleConnectionService.EXTRA_PACKET, packet);
            sendBroadcast(intent);

            clusterHandler.postDelayed(this, CLUSTER_INTERVAL_MS);
        }
    };

    private void sendIdentificationPacket() {
        SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        boolean usesImg = prefs.getBoolean("usesInvertedChecksum", false);
        String userName = prefs.getString("user_name", "USER");

        SharedPreferences prefsObj = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        String brandName = prefsObj.getString("brand_name", "suzuki");
        VehicleProtocol protocol = VehicleProtocolFactory.getProtocol(brandName);
        byte[] packet = protocol.buildIdentificationPacket(userName, false, usesImg);
        Intent intent = new Intent(BleConnectionService.ACTION_SEND_PACKET);
        intent.setPackage(getPackageName());
        intent.putExtra(BleConnectionService.EXTRA_PACKET, packet);
        sendBroadcast(intent);
    }

    private void startTripRecording(String originName, String destName) {
        tripStartOdometer = -1;
        tripTopSpeedKmh = 0;
        tripTotalSpeedSum = 0;
        tripSpeedSamples = 0;
        currentTripId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        
        final String fOrigin = (originName == null) ? "Start" : originName;
        final String fDest = (destName == null) ? "Destination" : destName;

        realm.executeTransactionAsync(r -> {
            TripRecord trip = r.createObject(TripRecord.class, currentTripId);
            trip.setStartTimeMs(now);
            trip.setStartPlaceName(fOrigin);
            trip.setEndPlaceName(fDest);
            trip.setStatus("IN_PROGRESS");
        });
    }

    private void finalizeTripRecording(boolean destinationReached) {
        if (currentTripId == null) return;
        final String tripId = currentTripId;
        currentTripId = null;

        long endTime = System.currentTimeMillis();
        int topSpeed = tripTopSpeedKmh;
        float avgSpeed = tripSpeedSamples > 0 ? (float) (tripTotalSpeedSum / tripSpeedSamples) : 0f;

        realm.executeTransactionAsync(r -> {
            TripRecord trip = r.where(TripRecord.class).equalTo("id", tripId).findFirst();
            if (trip != null) {
                if (trip.getDistanceKm() == 0 && tripStartOdometer >= 0) {
                    trip.setDistanceKm(0);
                }
                trip.setEndTimeMs(endTime);
                trip.setTopSpeedKmh(topSpeed);
                trip.setAvgSpeedKmh(avgSpeed);
                trip.setStatus("COMPLETED");
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Navigation Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, NavigationActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Stop Action
        Intent stopIntent = new Intent(this, NavigationService.class);
        stopIntent.setAction("STOP_NAVIGATION");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SConnect Navigation")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_location_pin) // Using valid drawable
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String instruction, int distance) {
        String text = instruction + " in " + distance + "m";
        Notification notification = buildNotification(text);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
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

                poly.add(new LatLng((double) lat / 1E6, (double) lng / 1E6));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding polyline", e);
        }
        return poly;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "NavigationService destroyed");
        isNavigationStarted = false;
        clusterHandler.removeCallbacks(clusterRunnable);
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (navigationSession != null) {
            navigationSession.stopSession();
        }
        try {
            unregisterReceiver(vehicleDataReceiver);
        } catch (Exception ignored) {}
        if (realm != null && !realm.isClosed()) {
            realm.close();
        }
        NavigationStateHolder.getInstance().clear();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
