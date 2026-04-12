package com.suzuki.sconnect.data;

import com.suzuki.sconnect.data.model.ServiceReminder;
import com.suzuki.sconnect.utils.DebugLogger;

import java.util.ArrayList;
import java.util.List;

import io.realm.Realm;

/**
 * Manager class for handling service reminders.
 */
public class ServiceReminderManager {
    private static final String TAG = "ServiceReminderManager";

    /**
     * Initializes default service reminders if they don't exist in Realm.
     */
    public static void initDefaultReminders() {
        try (Realm realm = Realm.getDefaultInstance()) {
            if (realm.where(ServiceReminder.class).count() > 0) {
                DebugLogger.i(TAG, "Service reminders already initialized.");
                return;
            }

            DebugLogger.i(TAG, "Initializing default service reminders...");

            realm.executeTransaction(r -> {
                // Standard Suzuki service items based on official app logic
                r.copyToRealmOrUpdate(new ServiceReminder("periodic_service", "Periodic Service", 120, 4000, "ic_service"));
                r.copyToRealmOrUpdate(new ServiceReminder("brake_oil", "Brake Oil Change", 730, 0, "ic_brake"));
                r.copyToRealmOrUpdate(new ServiceReminder("spark_plug", "Spark Plug change", 240, 8000, "ic_spark"));
                r.copyToRealmOrUpdate(new ServiceReminder("air_filter", "Air Filter replacement", 365, 12000, "ic_filter"));
                r.copyToRealmOrUpdate(new ServiceReminder("battery_check", "Battery Checkup", 120, 4000, "ic_battery"));
            });

            DebugLogger.i(TAG, "Default service reminders initialized successfully.");
        } catch (Exception e) {
            DebugLogger.e(TAG, "Failed to initialize default service reminders", e);
        }
    }

    /**
     * Returns the count of reminders that are currently due.
     */
    public static int getDueRemindersCount(int currentOdometer) {
        int count = 0;
        long now = System.currentTimeMillis();

        try (Realm realm = Realm.getDefaultInstance()) {
            List<ServiceReminder> reminders = realm.where(ServiceReminder.class).equalTo("isEnabled", true).findAll();
            for (ServiceReminder reminder : reminders) {
                boolean isDateDue = reminder.getIntervalDays() > 0 && now >= reminder.getNextServiceDate();
                boolean isOdoDue = reminder.getIntervalKm() > 0 && currentOdometer >= reminder.getNextServiceOdometer();

                if (isDateDue || isOdoDue) {
                    count++;
                }
            }
        }
        return count;
    }
}
