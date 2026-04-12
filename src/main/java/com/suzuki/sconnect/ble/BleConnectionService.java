package com.suzuki.sconnect.ble;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ui.activities.MainActivity;
import com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder;
import com.suzuki.sconnect.ble.protocol.SuzukiPacketParser;
import com.suzuki.sconnect.utils.DebugLogger;
import com.suzuki.sconnect.data.model.DailyFuelRecord;
import com.suzuki.sconnect.utils.VehicleStateHolder;

import io.realm.Realm;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground BLE Service with extensive debug broadcasts
 */
public class BleConnectionService extends Service {
    private static final String CHANNEL_ID = "SCONNECT_SERVICE_CHANNEL";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STATE_CHANGE = "com.suzuki.sconnect.STATE_CHANGE";
    public static final String ACTION_VEHICLE_DATA = "com.suzuki.sconnect.VEHICLE_DATA";
    public static final String ACTION_ERROR = "com.suzuki.sconnect.ERROR";

    public static final String ACTION_SEND_PACKET = "com.suzuki.sconnect.SEND_PACKET";
    public static final String EXTRA_PACKET = "packet_data";

    private BluetoothGatt bluetoothGatt;
    private SuzukiGattCallback gattCallback;
    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private String userName;
    private boolean usesInvertedChecksum; // true for 'A' prefix (Access/Burgman), false for 'B' prefix (SBM/Avenis)
    private boolean isIdentificationSent = false;
    private int heartbeatCount = 0;

    // Real telemetry state for heartbeat packet
    private int lastSpeedKmh = 0;   // Updated from ?7 cluster packet
    private String lastSignalLevel = "1"; // "0"–"3", updated by PhoneStateListener

    // ── P3: Fuel Consumption Tracking ────────────────────────────────────────────────────
    private int startOdometer = -1;           // ODO at connection time (in km)
    private double cumulativeFuelConsumed = 0.0; // accumulated fuel since connection (litres)
    private double lastMileageKmL = 0.0;      // last computed mileage

    // ── P5: Incremental Daily Persistence ───────────────────────────────────────────────
    private double lastPersistedDistance = 0.0;
    private double lastPersistedFuel = 0.0;
    // ─────────────────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────────────────

    private TelephonyManager telephonyManager;
    private PhoneStateListener signalListener;

    private final android.content.BroadcastReceiver packetReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            DebugLogger.d("Service", "onReceive called with action: " + intent.getAction());
            if (ACTION_SEND_PACKET.equals(intent.getAction())) {
                byte[] packet = intent.getByteArrayExtra(EXTRA_PACKET);
                if (packet != null && bluetoothGatt != null && gattCallback != null) {
                    DebugLogger.d("Service", "Received request to send packet: " + packet.length + " bytes");

                    // Decompiled code sends packets 3 times with 200ms delay for reliability
                    // Use a background thread to avoid blocking
                    final byte[] finalPacket = packet;
                    new Thread(() -> {
                        for (int i = 0; i < 3; i++) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                DebugLogger.e("Service", "Interrupted during packet delay", e);
                            }
                            if (bluetoothGatt != null && gattCallback != null) {
                                DebugLogger.d("Service", "Sending packet attempt " + (i + 1) + "/3");
                                gattCallback.writePacket(bluetoothGatt, finalPacket);
                            }
                        }
                    }).start();
                } else {
                    DebugLogger.w("Service", "Cannot send packet: " +
                            (packet == null ? "null packet" : (bluetoothGatt == null ? "gatt null" : "callback null")));
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        DebugLogger.i("Service", "=== BLE Service Created ===");

        IntentFilter filter = new IntentFilter(ACTION_SEND_PACKET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packetReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packetReceiver, filter);
        }
        DebugLogger.d("Service", "Receiver registered for: " + ACTION_SEND_PACKET);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLogger.i("Service", "=== BLE Service Started ===");

        Notification notification = buildNotification("Waiting for vehicle...", "Scanning in background");
        startForeground(NOTIFICATION_ID, notification);

        BluetoothDevice device = null;
        String deviceName = "Suzuki";

        if (intent != null && intent.hasExtra("device")) {
            device = intent.getParcelableExtra("device");
            userName = intent.getStringExtra("userName");
            deviceName = intent.getStringExtra("deviceName");

            getSharedPreferences("SConnectPrefs", MODE_PRIVATE)
                    .edit()
                    .putString("ble_mac_address", device.getAddress())
                    .putString("ble_device_name", deviceName)
                    .putString("ble_user_name", userName)
                    .apply();
        } else {
            SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
            String macAddress = prefs.getString("ble_mac_address", null);
            if (macAddress != null) {
                android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(android.content.Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled()) {
                    device = bm.getAdapter().getRemoteDevice(macAddress);
                    deviceName = prefs.getString("ble_device_name", "Suzuki");
                    userName = prefs.getString("ble_user_name", "USER");
                    DebugLogger.i("Service", "Auto-recovering device from SharedPreferences: " + macAddress);
                }
            }
        }

        if (device != null) {
            usesInvertedChecksum = SuzukiPacketBuilder.usesInvertedChecksum(deviceName);

            DebugLogger.i("Service", "Connection parameters:");
            DebugLogger.d("Service", "  Device: " + deviceName);
            DebugLogger.d("Service", "  Address: " + device.getAddress());
            DebugLogger.d("Service", "  Username: " + userName);
            DebugLogger.d("Service", "  Checksum Type: " + (usesInvertedChecksum ? "INVERTED (255-sum)" : "DIRECT (sum)"));

            getSharedPreferences("SConnectPrefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("usesInvertedChecksum", usesInvertedChecksum)
                    .apply();

            connectToDevice(device);
        } else {
            DebugLogger.w("Service", "onStartCommand with null device and no saved MAC. Stopping self.");
            stopSelf();
        }

        return START_STICKY;
    }

    private void connectToDevice(BluetoothDevice device) {
        DebugLogger.i("Service", "=== Initiating Connection ===");
        broadcastStateChange("CONNECTING");

        gattCallback = new SuzukiGattCallback(new SuzukiGattCallback.ConnectionListener() {
            @Override
            public void onConnected() {
                updateNotification("Connected", "Discovering services...");
                broadcastStateChange("CONNECTED");
            }

            @Override
            public void onDisconnected() {
                updateNotification("Disconnected", "");
                broadcastStateChange("DISCONNECTED");
                stopHeartbeat();

                // ── P1: Save Last Parked Location ──────────────────────────────────────
                // On disconnect, snapshot the last known GPS position so
                // LastParkedLocationActivity can show where the bike was parked.
                saveLastParkedLocation();
                // ──────────────────────────────────────────────────────────────────────

                // Reset fuel tracking so next connection starts fresh
                startOdometer = -1;
                cumulativeFuelConsumed = 0.0;
                lastMileageKmL = 0.0;
            }

            @Override
            public void onReady() {
                updateNotification("Ready", "Sending identification...");
                broadcastStateChange("READY");
                sendIdentificationPackets();
            }

            @Override
            public void onVehicleDataReceived(SuzukiPacketParser.VehicleData data) {
                DebugLogger.i("Service",
                        String.format(
                                "onVehicleDataReceived: Speed=%d, ODO=%d, TripA=%.1f, TripB=%.1f, Gear=%c, Fuel=%d",
                                data.speed, data.odometer, data.tripA, data.tripB, data.gear, data.fuelLevel));

                // Cache speed for use in the next heartbeat packet
                lastSpeedKmh = data.speed;
                DebugLogger.d("Service", "Heartbeat speed updated: " + lastSpeedKmh + " km/h");

                // ── P3: Fuel Consumption Accumulation ────────────────────────────────────
                // Initialise start ODO on first packet after connection
                if (startOdometer < 0) {
                    startOdometer = data.odometer;
                    DebugLogger.i("Service", "P3: Start ODO set to " + startOdometer + " km");
                }

                // Accumulate instantaneous fuel consumption (litres per cluster update)
                if (data.fuelConsumption > 0) {
                    cumulativeFuelConsumed += data.fuelConsumption;
                }

                // Compute mileage: km / L
                double distanceTravelled = (data.odometer - startOdometer); // km (ODO is in km)
                if (distanceTravelled > 0 && cumulativeFuelConsumed > 0) {
                    lastMileageKmL = (distanceTravelled * 1000.0) / cumulativeFuelConsumed;
                    // cap at a sanity maximum (200 km/L) to filter out stale/zero fuel readings
                    if (lastMileageKmL > 200.0) lastMileageKmL = 0.0;
                }
                DebugLogger.d("Service", String.format("P3: FC=%.4fL cumulative=%.4fL dist=%.1fkm mileage=%.1f km/L",
                        data.fuelConsumption, cumulativeFuelConsumed, distanceTravelled, lastMileageKmL));

                // ── P5: Daily Fuel Aggregate Persistence ─────────────────────────────────
                double distDelta = distanceTravelled - lastPersistedDistance;
                double fuelDelta = cumulativeFuelConsumed - lastPersistedFuel;

                // Persist if there's any significant change (to avoid excessive Realm writes)
                if (distDelta > 0 || fuelDelta > 0.0001) {
                    updateDailyFuelRecord((float) distDelta, (float) fuelDelta);
                    lastPersistedDistance = distanceTravelled;
                    lastPersistedFuel = cumulativeFuelConsumed;
                }
                // ─────────────────────────────────────────────────────────────────────────

                // Update singleton state holder
                VehicleStateHolder.getInstance().updateState(
                        BleConnectionService.this,
                        data.speed,
                        data.odometer,
                        data.tripA,
                        data.tripB,
                        data.gear,
                        data.fuelLevel,
                        (float) lastMileageKmL
                );

                // Broadcast data to UI
                Intent intent = new Intent(ACTION_VEHICLE_DATA);

                // Save last ODO to prefs for offline access (P7)
                getSharedPreferences("SConnectPrefs", MODE_PRIVATE)
                        .edit()
                        .putInt("last_odometer", data.odometer)
                        .apply();

                intent.setPackage(getPackageName()); // Explicit package for internal broadcast
                intent.putExtra("speed", data.speed);
                intent.putExtra("odometer", data.odometer);
                intent.putExtra("tripA", data.tripA);
                intent.putExtra("tripB", data.tripB);
                intent.putExtra("gear", (int) data.gear); // Cast to int for proper transmission
                intent.putExtra("fuelLevel", data.fuelLevel);
                intent.putExtra("mileageKmL", (float) lastMileageKmL); // P3: mileage

                DebugLogger.d("Service", "Sending broadcast: " + ACTION_VEHICLE_DATA);
                sendBroadcast(intent);

                updateNotification("Connected", String.format("Speed: %d km/h | ODO: %d km | %.1f km/L",
                        data.speed, data.odometer, lastMileageKmL));
            }

            @Override
            public void onError(String error) {
                DebugLogger.e("Service", "Connection error: " + error);
                broadcastError(error);
            }
        }, usesInvertedChecksum);

        try {
            DebugLogger.d("Service", "Calling connectGatt with:");
            DebugLogger.d("Service", "  autoConnect: true");
            DebugLogger.d("Service", "  transport: TRANSPORT_LE (2)");

            bluetoothGatt = device.connectGatt(this, true, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);

            if (bluetoothGatt != null) {
                DebugLogger.i("Service", "✓ connectGatt() returned non-null BluetoothGatt");
            } else {
                DebugLogger.e("Service", "✗ connectGatt() returned null!");
                broadcastError("connectGatt returned null");
            }
        } catch (SecurityException e) {
            DebugLogger.e("Service", "Permission denied for connectGatt", e);
            broadcastError("Permission denied");
        }
    }

    private void sendIdentificationPackets() {
        DebugLogger.i("Service", "=== Sending Identification Packets ===");
        DebugLogger.d("Service", "Will send ?6 packet ONCE (as per decompiled initialization logic)");

        // Decompiled code sends ?6 packet only once (C0704v.java: if (i <= 1) ...
        // this.q++)
        // isNewConnection=true ('F') to force Welcome message/pairing
        byte[] packet = SuzukiPacketBuilder.buildIdentificationPacket(userName, true, usesInvertedChecksum);
        gattCallback.writePacket(bluetoothGatt, packet);

        DebugLogger.i("Service", "✓ Identification packet sent");
        DebugLogger.i("Service", "=== Starting Heartbeat ===");

        // Start heartbeat immediately after identification
        isIdentificationSent = true;
        startSignalStrengthListener();
        startHeartbeat();
    }

    private void startHeartbeat() {
        heartbeatCount = 0;
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                heartbeatHandler.postDelayed(this, 1000); // Every 1 second
            }
        };
        heartbeatHandler.post(heartbeatRunnable);
        DebugLogger.i("Service", "Heartbeat timer started (1000ms interval)");
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            DebugLogger.i("Service", "Heartbeat timer stopped (sent " + heartbeatCount + " packets)");
        }
    }

    /**
     * Registers a PhoneStateListener to track cellular signal strength.
     * Maps Android signal level (0–4) → cluster value ("0"–"3") matching
     * the decompiled C0707y.onSignalStrengthsChanged() logic.
     */
    @SuppressWarnings("deprecation") // PhoneStateListener is deprecated in API 31 but still works
    private void startSignalStrengthListener() {
        try {
            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            signalListener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    super.onSignalStrengthsChanged(signalStrength);
                    // Map Android levels 0–4 to cluster values 0–3
                    // (mirrors C0707y: level>=4 → "3", level==3 → "2", level==2 → "1", level<=1 → "0")
                    int level = signalStrength.getLevel();
                    if (level >= 4) {
                        lastSignalLevel = "3";
                    } else if (level == 3) {
                        lastSignalLevel = "2";
                    } else if (level == 2) {
                        lastSignalLevel = "1";
                    } else {
                        lastSignalLevel = "0";
                    }
                    DebugLogger.d("Signal", "Signal level updated: Android=" + level + " → cluster=" + lastSignalLevel);
                }
            };
            telephonyManager.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            DebugLogger.i("Service", "Signal strength listener registered");
        } catch (Exception e) {
            DebugLogger.e("Service", "Failed to register signal listener", e);
        }
    }

    private void stopSignalStrengthListener() {
        try {
            if (telephonyManager != null && signalListener != null) {
                telephonyManager.listen(signalListener, PhoneStateListener.LISTEN_NONE);
                DebugLogger.i("Service", "Signal strength listener unregistered");
            }
        } catch (Exception e) {
            DebugLogger.e("Service", "Failed to unregister signal listener", e);
        }
    }

    private void sendHeartbeat() {
        heartbeatCount++;

        // Get current time
        SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss", Locale.US);
        String time = timeFormat.format(new Date());

        // Get battery status (real)
        String batteryStatus = getBatteryStatus();

        // Format speed as 3-digit string, clamped to 0–999
        int clampedSpeed = Math.max(0, Math.min(999, lastSpeedKmh));
        String speedStr = String.format(Locale.US, "%03d", clampedSpeed);

        DebugLogger.d("Service", String.format(
                "Sending heartbeat #%d | time=%s battery=%s speed=%s signal=%s",
                heartbeatCount, time, batteryStatus, speedStr, lastSignalLevel));

        byte[] packet = SuzukiPacketBuilder.buildHeartbeatPacket(
                batteryStatus, speedStr, lastSignalLevel, time, usesInvertedChecksum);
        gattCallback.writePacket(bluetoothGatt, packet);
    }

    /**
     * Get battery status encoded as [0-3][Y/N]
     * - First digit: 0=0-24%, 1=25-49%, 2=50-74%, 3=75-100%
     * - Second char: Y=Charging, N=Not charging
     */
    private String getBatteryStatus() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);

            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

                // Calculate percentage
                float batteryPct = (level / (float) scale) * 100;
                int batteryPercent = Math.round(batteryPct);

                // Determine battery level digit (0-3)
                String levelDigit;
                if (batteryPercent >= 75) {
                    levelDigit = "3";
                } else if (batteryPercent >= 50) {
                    levelDigit = "2";
                } else if (batteryPercent >= 25) {
                    levelDigit = "1";
                } else {
                    levelDigit = "0";
                }

                // Determine charging status (Y/N)
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL);
                String chargingChar = isCharging ? "Y" : "N";

                String result = levelDigit + chargingChar;
                DebugLogger.d("Battery", String.format("Level: %d%% -> %s (%s)",
                        batteryPercent, result, isCharging ? "Charging" : "Not Charging"));

                return result;
            }
        } catch (Exception e) {
            DebugLogger.e("Battery", "Error reading battery status", e);
        }

        // Fallback to default
        DebugLogger.w("Battery", "Using fallback battery status: 1Y");
        return "1Y";
    }

    private void broadcastStateChange(String state) {
        Intent intent = new Intent(ACTION_STATE_CHANGE);
        intent.setPackage(getPackageName()); // Explicit package for internal broadcast
        intent.putExtra("state", state);
        sendBroadcast(intent);
    }

    /**
     * P1 — Last Parked Location
     * Snapshots the last known GPS position into SharedPreferences when BLE disconnects.
     * Uses LocationManager.getLastKnownLocation() — no continuous GPS drain required.
     * Stores: last_parked_lat, last_parked_lng, last_parked_time (epoch ms).
     */
    private void saveLastParkedLocation() {
        try {
            boolean hasPermission = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            if (!hasPermission) {
                DebugLogger.w("Service", "P1: Location permission not granted — cannot save parked location");
                return;
            }

            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location location = null;

            // Prefer GPS provider; fall back to network provider
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (location == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }

            if (location != null) {
                SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
                prefs.edit()
                        .putFloat("last_parked_lat", (float) location.getLatitude())
                        .putFloat("last_parked_lng", (float) location.getLongitude())
                        .putLong("last_parked_time", System.currentTimeMillis())
                        .apply();

                DebugLogger.i("Service", String.format(
                        "P1: Parked location saved — lat=%.5f, lng=%.5f",
                        location.getLatitude(), location.getLongitude()));
            } else {
                DebugLogger.w("Service", "P1: No GPS fix available at disconnect time — parked location not saved");
            }
        } catch (Exception e) {
            DebugLogger.e("Service", "P1: Error saving parked location", e);
        }
    }

    /**
     * P5: Updates the DailyFuelRecord in Realm for the current date.
     */
    private void updateDailyFuelRecord(float distanceDelta, float fuelDelta) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        try (Realm realm = Realm.getDefaultInstance()) {
            realm.executeTransaction(r -> {
                DailyFuelRecord record = r.where(DailyFuelRecord.class)
                        .equalTo("date", today)
                        .findFirst();

                if (record == null) {
                    record = r.createObject(DailyFuelRecord.class, today);
                }

                record.addProgress(distanceDelta, fuelDelta);
                DebugLogger.d("Service", String.format("P5: Updated DailyFuelRecord for %s (+%.3fkm, +%.4fL)",
                        today, distanceDelta, fuelDelta));
            });
        } catch (Exception e) {
            DebugLogger.e("Service", "P5: Failed to update DailyFuelRecord", e);
        }
    }

    private void broadcastError(String error) {
        Intent intent = new Intent(ACTION_ERROR);
        intent.setPackage(getPackageName()); // Explicit package for internal broadcast
        intent.putExtra("error", error);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SConnect Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("SConnect Bluetooth Connectivity Service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
            DebugLogger.d("Service", "Notification channel created");
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("S-Connect: " + title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String content) {
        Notification notification = buildNotification(title, content);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DebugLogger.i("Service", "=== BLE Service Destroyed ===");

        // Unregister broadcast receiver to prevent leak
        try {
            unregisterReceiver(packetReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }

        stopSignalStrengthListener();
        stopHeartbeat();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                DebugLogger.i("Service", "GATT connection closed");
            } catch (SecurityException e) {
                DebugLogger.e("Service", "Permission denied when closing GATT", e);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
