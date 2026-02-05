package com.suzuki.sconnect.ble;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ui.activities.MainActivity;
import com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder;
import com.suzuki.sconnect.ble.protocol.SuzukiPacketParser;
import com.suzuki.sconnect.utils.DebugLogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground BLE Service with extensive debug broadcasts
 */
public class BleConnectionService extends Service {
    private static final String CHANNEL_ID = "BLE_DEBUG_CHANNEL";
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

    private final android.content.BroadcastReceiver packetReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
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
            registerReceiver(packetReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(packetReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLogger.i("Service", "=== BLE Service Started ===");

        // Start as foreground service
        Notification notification = buildNotification("Initializing...", "");
        startForeground(NOTIFICATION_ID, notification);

        if (intent == null) {
            return START_STICKY;
        }

        // Get connection parameters from intent
        BluetoothDevice device = intent.getParcelableExtra("device");
        userName = intent.getStringExtra("userName");
        String deviceName = intent.getStringExtra("deviceName");

        // Only re-initialize if we have a valid device, otherwise we might be
        // restarting from stickiness
        // or just receiving a command without re-connection intent
        if (device != null) {
            usesInvertedChecksum = SuzukiPacketBuilder.usesInvertedChecksum(deviceName);

            DebugLogger.i("Service", "Connection parameters:");
            DebugLogger.d("Service", "  Device: " + deviceName);
            DebugLogger.d("Service", "  Address: " + (device != null ? device.getAddress() : "null"));
            DebugLogger.d("Service", "  Username: " + userName);
            DebugLogger.d("Service",
                    "  Checksum Type: " + (usesInvertedChecksum ? "INVERTED (255-sum)" : "DIRECT (sum)"));

            // Save checksum type to prefs for other components
            // (NotificationService/CallReceiver)
            getSharedPreferences("SConnectPrefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("usesInvertedChecksum", usesInvertedChecksum)
                    .apply();

            connectToDevice(device);
        } else {
            // Check if we are already connected?
            // For now just log, if we are sticky restart with null intent we wait.
            DebugLogger.w("Service", "onStartCommand with null device (sticky restart?)");
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
                        String.format("onVehicleDataReceived: ODO=%d, TripA=%.1f, TripB=%.1f, Gear=%c, Fuel=%d",
                                data.odometer, data.tripA, data.tripB, data.gear, data.fuelLevel));

                // Broadcast data to UI
                Intent intent = new Intent(ACTION_VEHICLE_DATA);
                intent.setPackage(getPackageName()); // Explicit package for internal broadcast
                intent.putExtra("odometer", data.odometer);
                intent.putExtra("tripA", data.tripA);
                intent.putExtra("tripB", data.tripB);
                intent.putExtra("gear", (int) data.gear); // Cast to int for proper transmission
                intent.putExtra("fuelLevel", data.fuelLevel);

                DebugLogger.d("Service", "Sending broadcast: " + ACTION_VEHICLE_DATA);
                sendBroadcast(intent);

                updateNotification("Connected", String.format("ODO: %d km | Fuel: %d bars",
                        data.odometer, data.fuelLevel));
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

    private void sendHeartbeat() {
        heartbeatCount++;

        // Get current time
        SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss", Locale.US);
        String time = timeFormat.format(new Date());

        // Get battery status
        String batteryStatus = getBatteryStatus();

        DebugLogger.d("Service", String.format("Sending heartbeat #%d (time: %s, battery: %s)",
                heartbeatCount, time, batteryStatus));

        byte[] packet = SuzukiPacketBuilder.buildHeartbeatPacket(batteryStatus, time, usesInvertedChecksum);
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
                    "BLE Debug Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Suzuki BLE connection debug service");
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
