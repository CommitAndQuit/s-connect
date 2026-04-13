package com.suzuki.sconnect;

import android.app.Application;
import com.mappls.sdk.maps.Mappls;
import com.mappls.sdk.services.account.MapplsAccountManager;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.suzuki.sconnect.notifications.ServiceReminderWorker;
import java.util.concurrent.TimeUnit;

/**
 * Custom Application class.
 */
public class SConnectApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize Mappls SDK here for global context
        MapplsAccountManager.getInstance().setRestAPIKey(BuildConfig.MAPPLS_REST_API_KEY);
        MapplsAccountManager.getInstance().setMapSDKKey(BuildConfig.MAPPLS_MAP_SDK_KEY);
        MapplsAccountManager.getInstance().setAtlasClientId(BuildConfig.MAPPLS_CLIENT_ID);
        MapplsAccountManager.getInstance().setAtlasClientSecret(BuildConfig.MAPPLS_CLIENT_SECRET);
        Mappls.getInstance(this);

        // Initialize Realm (P2 — Trip Recording)
        // Must be done before any Realm.getDefaultInstance() call.
        Realm.init(this);
        RealmConfiguration config = new RealmConfiguration.Builder()
                .name("sconnect.realm")
                .schemaVersion(1)
                .allowWritesOnUiThread(true)   // Needed for executeTransactionAsync callbacks
                .deleteRealmIfMigrationNeeded() // Dev convenience — remove for production
                .build();
        Realm.setDefaultConfiguration(config);

        // Initialize default service reminders (P7 — Service Reminders)
        com.suzuki.sconnect.data.ServiceReminderManager.initDefaultReminders();

        // Schedule periodic maintenance checks (once every 24 hours)
        scheduleServiceChecks();
    }

    private void scheduleServiceChecks() {
        PeriodicWorkRequest serviceCheckRequest = new PeriodicWorkRequest.Builder(
                ServiceReminderWorker.class, 24, TimeUnit.HOURS)
                .addTag("SERVICE_CHECK")
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ServiceReminderWork",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                serviceCheckRequest);
    }
}
