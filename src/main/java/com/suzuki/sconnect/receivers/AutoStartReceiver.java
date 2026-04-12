package com.suzuki.sconnect.receivers;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.suzuki.sconnect.ble.BleConnectionService;

public class AutoStartReceiver extends BroadcastReceiver {
    private static final String TAG = "AutoStartReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            // Check if bluetooth is actually ON
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.d(TAG, "Bluetooth not enabled, skipping auto-start");
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences("SConnectPrefs", Context.MODE_PRIVATE);
            String macAddress = prefs.getString("ble_mac_address", null);

            if (macAddress != null && !macAddress.isEmpty()) {
                Log.i(TAG, "Found saved vehicle MAC: " + macAddress + ". Auto-starting BleConnectionService.");
                Intent serviceIntent = new Intent(context, BleConnectionService.class);
                serviceIntent.setAction("AUTO_START");
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "No saved vehicle MAC address found. Not starting service.");
            }
        }
    }
}
