package com.suzuki.sconnect;

import android.app.Application;
import com.mappls.sdk.maps.Mappls;
import com.mappls.sdk.services.account.MapplsAccountManager;
import io.realm.Realm;
import io.realm.RealmConfiguration;

/**
 * Custom Application class.
 */
public class SConnectApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize Mappls SDK here for global context
        MapplsAccountManager.getInstance().setRestAPIKey(getString(R.string.mappls_rest_api_key));
        MapplsAccountManager.getInstance().setMapSDKKey(getString(R.string.mappls_map_sdk_key));
        MapplsAccountManager.getInstance().setAtlasClientId(getString(R.string.mappls_client_id));
        MapplsAccountManager.getInstance().setAtlasClientSecret(getString(R.string.mappls_client_secret));
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
    }
}
