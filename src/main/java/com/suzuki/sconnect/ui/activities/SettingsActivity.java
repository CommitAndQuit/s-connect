package com.suzuki.sconnect.ui.activities;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private ImageButton btnBack;

    private EditText etUsername;
    private TextView tvStatus;
    private Button btnDisconnect;
    private Button btnTestNavigationSequence;

    private android.widget.Switch switchNotifications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        btnBack = findViewById(R.id.btnBack);

        etUsername = findViewById(R.id.etUsernameSettings);
        tvStatus = findViewById(R.id.tvSettingsStatus);
        btnDisconnect = findViewById(R.id.btnDisconnectSettings);
        btnTestNavigationSequence = findViewById(R.id.btnTestNavigationSequence);
        switchNotifications = findViewById(R.id.switchNotifications);

        btnBack.setOnClickListener(v -> finish());

        // Notification Access Toggle
        switchNotifications.setOnClickListener(v -> {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            startActivity(intent);
        });

        // Load existing username if any
        // etUsername.setText(...)

        btnDisconnect.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, BleConnectionService.class);
            stopService(serviceIntent);
            tvStatus.setText("Status: Disconnected");
            btnDisconnect.setVisibility(View.GONE);
        });

        btnTestNavigationSequence.setOnClickListener(v -> runNavigationTestSequence());
    }

    private void runNavigationTestSequence() {
        btnTestNavigationSequence.setEnabled(false);
        android.util.Log.d("SuzukiBLE", "SettingsActivity: Starting navigation test sequence...");
        android.widget.Toast.makeText(this, "Starting Navigation Test Sequence...", android.widget.Toast.LENGTH_SHORT)
                .show();

        // Get checksum type from preferences
        boolean usesInvertedChecksum = getSharedPreferences("SConnectPrefs", MODE_PRIVATE)
                .getBoolean("usesInvertedChecksum", false);

        new Thread(() -> {
            try {
                // 1. Send Identification Packet (?6) to ensure cluster initialization
                android.util.Log.d("SuzukiBLE", "SettingsActivity: Sending initial ?6 packet");
                byte[] idPacket = com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder.buildIdentificationPacket(
                        "TESTER", true, usesInvertedChecksum);
                sendPacketBroadcast(idPacket);
                Thread.sleep(2000); // Wait for cluster to process

                // 2. Test IDs from 0 to 76 to find mapped icons
                for (int i = 0; i <= 76; i++) {
                    final int turnId = i;

                    runOnUiThread(() -> {
                        android.widget.Toast
                                .makeText(this, "Testing Turn Icon ID: " + turnId, android.widget.Toast.LENGTH_SHORT)
                                .show();
                    });

                    android.util.Log.d("SuzukiBLE", "SettingsActivity: Building navigation packet for ID " + turnId);

                    // Build fixed packet with correct structure
                    byte[] packet = com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder.buildNavigationPacket(
                            250, turnId, "0530PM", "1", usesInvertedChecksum);

                    if (packet != null) {
                        sendPacketBroadcast(packet);
                        android.util.Log.d("SuzukiBLE", "SettingsActivity: Packet sent for ID " + turnId);
                    }

                    // Wait 3 seconds for observation before next packet
                    Thread.sleep(3000);
                }

                android.util.Log.d("SuzukiBLE", "SettingsActivity: Navigation test sequence completed!");
                runOnUiThread(() -> {
                    android.widget.Toast
                            .makeText(this, "Navigation Test Sequence Completed!", android.widget.Toast.LENGTH_LONG)
                            .show();
                    btnTestNavigationSequence.setEnabled(true);
                });

            } catch (InterruptedException e) {
                android.util.Log.e("SuzukiBLE", "SettingsActivity: Test sequence interrupted!", e);
                runOnUiThread(() -> {
                    btnTestNavigationSequence.setEnabled(true);
                });
            }
        }).start();
    }

    private void sendPacketBroadcast(byte[] packet) {
        if (packet == null)
            return;
        Intent intent = new Intent(BleConnectionService.ACTION_SEND_PACKET);
        intent.setPackage(getPackageName());
        intent.putExtra(BleConnectionService.EXTRA_PACKET, packet);
        sendBroadcast(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationPermission();
    }

    private void checkNotificationPermission() {
        if (switchNotifications == null)
            return;

        String packageName = getPackageName();
        String flat = android.provider.Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        boolean enabled = flat != null && flat.contains(packageName);

        switchNotifications.setChecked(enabled);
    }
}
