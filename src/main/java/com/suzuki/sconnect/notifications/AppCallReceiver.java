package com.suzuki.sconnect.notifications;

import com.suzuki.sconnect.ble.BleConnectionService;
import com.suzuki.sconnect.ble.protocol.VehicleProtocol;
import com.suzuki.sconnect.ble.protocol.VehicleProtocolFactory;
import com.suzuki.sconnect.utils.DebugLogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;

/**
 * Receiver for Phone State changes (Calls)
 */
public class AppCallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

            DebugLogger.d("CallReceiver", "State: " + state + ", Number: " + incomingNumber);

            if (state == null)
                return;

            // Resolve Name
            String name = incomingNumber;
            if (incomingNumber != null && !incomingNumber.isEmpty()) {
                String contactName = getContactName(context, incomingNumber);
                if (contactName != null) {
                    name = contactName;
                }
            } else {
                name = "Unknown";
            }

            // Get Checksum Pref
            SharedPreferences prefs = context.getSharedPreferences("SConnectPrefs", Context.MODE_PRIVATE);
            boolean usesInvertedChecksum = prefs.getBoolean("usesInvertedChecksum", false);

            byte[] packet = null;

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                // Status 1 = Incoming
                SharedPreferences prefsObj1 = context.getSharedPreferences("SConnectPrefs", Context.MODE_PRIVATE);
                String brandName1 = prefsObj1.getString("brand_name", "suzuki");
                VehicleProtocol protocol1 = VehicleProtocolFactory.getProtocol(brandName1);
                packet = protocol1.buildCallPacket(name, 1, usesInvertedChecksum);
            } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                // Status 2 = Ongoing
                SharedPreferences prefsObj2 = context.getSharedPreferences("SConnectPrefs", Context.MODE_PRIVATE);
                String brandName2 = prefsObj2.getString("brand_name", "suzuki");
                VehicleProtocol protocol2 = VehicleProtocolFactory.getProtocol(brandName2);
                packet = protocol2.buildCallPacket(name, 2, usesInvertedChecksum);
            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                // Missed call logic usually requires tracking state transitions (Ringing ->
                // Idle without Offhook)
                // For simplicity, we might just stop sending call packets?
                // Or if we can detect missed call, send ?4.
                // The native Android CallLog is better for missed calls, but that requires
                // ContentObserver.
                // For now, let's just assume we might want to clear the cluster's call screen?
                // The protocol doesn't seem to have a clear 'End Call' packet other than maybe
                // sending empty/default status?
                // We'll leave IDLE empty for now or maybe implement Missed Call if we track
                // previous state.
                return;
            }

            if (packet != null) {
                Intent packetIntent = new Intent(BleConnectionService.ACTION_SEND_PACKET);
                packetIntent.putExtra(BleConnectionService.EXTRA_PACKET, packet);
                packetIntent.setPackage(context.getPackageName());
                context.sendBroadcast(packetIntent);
            }
        }
    }

    private String getContactName(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty())
            return null;

        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        String[] projection = new String[] { ContactsContract.PhoneLookup.DISPLAY_NAME };

        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                if (index != -1) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception e) {
            DebugLogger.e("CallReceiver", "Error resolving contact", e);
        }
        return null; // Return null to use number
    }
}
