package com.suzuki.sconnect.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.TripRecord;
import com.suzuki.sconnect.ui.adapters.TripAdapter;

import java.util.ArrayList;
import java.util.List;

import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;

/**
 * P2 — Trip History Activity
 *
 * Displays a reverse-chronological list of all recorded trips from Realm DB.
 * Tapping a trip opens {@link TripDetailActivity}.
 */
public class TripHistoryActivity extends AppCompatActivity {

    private RecyclerView rvTrips;
    private LinearLayout layoutEmpty;
    private TripAdapter tripAdapter;
    private Realm realm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trip_history);

        Toolbar toolbar = findViewById(R.id.toolbar_trips);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvTrips = findViewById(R.id.rv_trips);
        layoutEmpty = findViewById(R.id.layout_empty_trips);

        rvTrips.setLayoutManager(new LinearLayoutManager(this));
        tripAdapter = new TripAdapter(new ArrayList<>(), this::openTripDetail);
        rvTrips.setAdapter(tripAdapter);

        realm = Realm.getDefaultInstance();
        loadTrips();
    }

    private void loadTrips() {
        // Fetch all COMPLETED trips, ordered newest first
        RealmResults<TripRecord> results = realm.where(TripRecord.class)
                .equalTo("status", "COMPLETED")
                .findAll()
                .sort("startTimeMs", Sort.DESCENDING);

        List<TripRecord> trips = realm.copyFromRealm(results);

        if (trips.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            rvTrips.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            rvTrips.setVisibility(View.VISIBLE);
            tripAdapter.updateTrips(trips);
        }
    }

    private void openTripDetail(TripRecord trip) {
        Intent intent = new Intent(this, TripDetailActivity.class);
        intent.putExtra("trip_id", trip.getId());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (realm != null && !realm.isClosed()) realm.close();
    }
}
