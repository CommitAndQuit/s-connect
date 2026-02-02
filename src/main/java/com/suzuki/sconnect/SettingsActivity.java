package com.suzuki.sconnect;

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
    private View cardDebugLogs;
    private EditText etUsername;
    private TextView tvStatus;
    private Button btnDisconnect;

    private android.widget.Switch switchNotifications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        btnBack = findViewById(R.id.btnBack);
        cardDebugLogs = findViewById(R.id.cardDebugLogs);
        etUsername = findViewById(R.id.etUsernameSettings);
        tvStatus = findViewById(R.id.tvSettingsStatus);
        btnDisconnect = findViewById(R.id.btnDisconnectSettings);
        switchNotifications = findViewById(R.id.switchNotifications);

        btnBack.setOnClickListener(v -> finish());

        cardDebugLogs.setOnClickListener(v -> {
            Intent intent = new Intent(this, DebugActivity.class);
            startActivity(intent);
        });

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
