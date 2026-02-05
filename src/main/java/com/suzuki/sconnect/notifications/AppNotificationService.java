package com.suzuki.sconnect.notifications;

import com.suzuki.sconnect.ble.BleConnectionService;
import com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder;
import com.suzuki.sconnect.utils.DebugLogger;

import android.app.Notification;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Service to capture notifications and send them to the instrument cluster.
 */
public class AppNotificationService extends NotificationListenerService {

    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.whatsapp",
            "com.whatsapp.w4b", // WhatsApp Business
            "com.google.android.apps.messaging", // Google Messages
            "com.android.mms", // Default SMS
            "com.samsung.android.messaging" // Samsung SMS
    // Add others as needed from the decompiled list
    ));

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        if (packageName == null || !ALLOWED_PACKAGES.contains(packageName)) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }

        // Ignore ongoing/permanent notifications
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return;
        }

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE);
        String text = extras.getString(Notification.EXTRA_TEXT);

        DebugLogger.d("NotificationService", "Posted: " + packageName + " - " + title);

        // Determine type
        int type = 1; // SMS/Default
        if (packageName.contains("whatsapp")) {
            type = 2;
        } else if (packageName.toLowerCase().contains("calendar")) {
            type = 3;
        }

        // Determine Checksum Type
        SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        boolean usesInvertedChecksum = prefs.getBoolean("usesInvertedChecksum", false); // Default to false (Direct)

        // Count logic is complex to mirror exactly without maintaining state,
        // For now, we will just send individual notification packets with a static
        // count (e.g. 1)
        // or try to count active notifications for that package.
        int count = 1;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            int c = 0;
            for (StatusBarNotification n : active) {
                if (n.getPackageName().equals(packageName)) {
                    c++;
                }
            }
            if (c > 0)
                count = c;
        } catch (Exception e) {
            // Ignore
        }

        // Use Title or Text? Decompiled service uses TickerText or Title.
        // We will prioritize Title (Sender Name)
        String display = title != null ? title : (text != null ? text : "Message");

        byte[] packet = SuzukiPacketBuilder.buildNotificationPacket(display, count, type, usesInvertedChecksum);

        // Send to BLE Service
        Intent intent = new Intent(BleConnectionService.ACTION_SEND_PACKET);
        intent.putExtra(BleConnectionService.EXTRA_PACKET, packet);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Optional: Handle removal to update counts?
        // Decompiled code only triggers on Posted for new alerts.
    }
}
