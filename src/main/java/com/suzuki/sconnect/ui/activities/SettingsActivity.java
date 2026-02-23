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

    private android.widget.Switch switchNotifications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        btnBack = findViewById(R.id.btnBack);

        etUsername = findViewById(R.id.etUsernameSettings);
        tvStatus = findViewById(R.id.tvSettingsStatus);
        btnDisconnect = findViewById(R.id.btnDisconnectSettings);
        switchNotifications = findViewById(R.id.switchNotifications);

        btnBack.setOnClickListener(v -> finish());

        // Notification Access Toggle
        switchNotifications.setOnClickListener(v -> {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            startActivity(intent);
        });

        // Load existing username if any
        String savedUser = getSharedPreferences("SConnectPrefs", MODE_PRIVATE).getString("user_name", "USER");
        etUsername.setText(savedUser);

        btnDisconnect.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, BleConnectionService.class);
            stopService(serviceIntent);
            tvStatus.setText("Status: Disconnected");
            btnDisconnect.setVisibility(View.GONE);
        });

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
    protected void onPause() {
        super.onPause();
        // Save username when leaving settings
        String newUser = etUsername.getText().toString().trim();
        if (!newUser.isEmpty()) {
            getSharedPreferences("SConnectPrefs", MODE_PRIVATE)
                    .edit()
                    .putString("user_name", newUser)
                    .apply();
        }
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
