package com.suzuki.sconnect.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.ServiceReminder;
import com.suzuki.sconnect.ui.activities.ServiceRemindersActivity;
import com.suzuki.sconnect.utils.DebugLogger;

import java.util.List;

import io.realm.Realm;

public class ServiceReminderWorker extends Worker {

    private static final String TAG = "ServiceReminderWorker";
    private static final String CHANNEL_ID = "SERVICE_REMINDER_CHANNEL";
    private static final int NOTIFICATION_ID = 2001;

    public ServiceReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        DebugLogger.i(TAG, "Starting background service check...");

        SharedPreferences prefs = getApplicationContext().getSharedPreferences("SConnectPrefs", Context.MODE_PRIVATE);
        int currentOdometer = prefs.getInt("last_odometer", 0);
        long now = System.currentTimeMillis();

        try (Realm realm = Realm.getDefaultInstance()) {
            List<ServiceReminder> reminders = realm.where(ServiceReminder.class).equalTo("isEnabled", true).findAll();
            
            int dueCount = 0;
            StringBuilder dueItems = new StringBuilder();

            for (ServiceReminder reminder : reminders) {
                boolean isDateDue = reminder.getIntervalDays() > 0 && now >= reminder.getNextServiceDate();
                boolean isOdoDue = reminder.getIntervalKm() > 0 && currentOdometer >= reminder.getNextServiceOdometer();

                if (isDateDue || isOdoDue) {
                    dueCount++;
                    if (dueItems.length() > 0) dueItems.append(", ");
                    dueItems.append(reminder.getName());
                }
            }

            if (dueCount > 0) {
                showNotification(dueCount, dueItems.toString());
            }
        } catch (Exception e) {
            DebugLogger.e(TAG, "Failed to check service reminders", e);
            return Result.retry();
        }

        return Result.success();
    }

    private void showNotification(int count, String items) {
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Service Reminders", NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(getApplicationContext(), ServiceRemindersActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, 
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String contentText = count == 1 ? "Service due: " + items : count + " maintenance tasks are due: " + items;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Should be a proper maintenance icon
                .setContentTitle("Vehicle Maintenance Due")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
        DebugLogger.i(TAG, "Service notification triggered for: " + items);
    }
}
