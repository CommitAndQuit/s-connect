package com.suzuki.bletest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Intent;
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

    public static final String ACTION_STATE_CHANGE = "com.suzuki.bletest.STATE_CHANGE";
    public static final String ACTION_VEHICLE_DATA = "com.suzuki.bletest.VEHICLE_DATA";
    public static final String ACTION_ERROR = "com.suzuki.bletest.ERROR";

    private BluetoothGatt bluetoothGatt;
    private SuzukiGattCallback gattCallback;
    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private String userName;
    private boolean usesInvertedChecksum; // true for 'A' prefix (Access/Burgman), false for 'B' prefix (SBM/Avenis)
    private boolean isIdentificationSent = false;
    private int heartbeatCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        DebugLogger.i("Service", "=== BLE Service Created ===");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLogger.i("Service", "=== BLE Service Started ===");

        // Start as foreground service
        Notification notification = buildNotification("Initializing...", "");
        startForeground(NOTIFICATION_ID, notification);

        // Get connection parameters from intent
        BluetoothDevice device = intent.getParcelableExtra("device");
        userName = intent.getStringExtra("userName");
        String deviceName = intent.getStringExtra("deviceName");
        usesInvertedChecksum = SuzukiPacketBuilder.usesInvertedChecksum(deviceName);

        DebugLogger.i("Service", "Connection parameters:");
        DebugLogger.d("Service", "  Device: " + deviceName);
        DebugLogger.d("Service", "  Address: " + (device != null ? device.getAddress() : "null"));
        DebugLogger.d("Service", "  Username: " + userName);
        DebugLogger.d("Service", "  Checksum Type: " + (usesInvertedChecksum ? "INVERTED (255-sum)" : "DIRECT (sum)"));

        if (device != null) {
            connectToDevice(device);
        } else {
            DebugLogger.e("Service", "Device is null! Cannot connect.");
            broadcastError("Device is null");
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
                // Broadcast data to UI
                Intent intent = new Intent(ACTION_VEHICLE_DATA);
                intent.putExtra("odometer", data.odometer);
                intent.putExtra("tripA", data.tripA);
                intent.putExtra("tripB", data.tripB);
                intent.putExtra("gear", data.gear);
                intent.putExtra("fuelLevel", data.fuelLevel);
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

        DebugLogger.d("Service", String.format("Sending heartbeat #%d (time: %s)", heartbeatCount, time));

        byte[] packet = SuzukiPacketBuilder.buildHeartbeatPacket(time, usesInvertedChecksum);
        gattCallback.writePacket(bluetoothGatt, packet);
    }

    private void broadcastStateChange(String state) {
        Intent intent = new Intent(ACTION_STATE_CHANGE);
        intent.putExtra("state", state);
        sendBroadcast(intent);
    }

    private void broadcastError(String error) {
        Intent intent = new Intent(ACTION_ERROR);
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
                .setContentTitle("Suzuki BLE: " + title)
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
