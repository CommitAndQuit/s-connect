package com.suzuki.sconnect.ui.activities;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.TripRecord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.realm.Realm;

/**
 * P2 — Trip Detail Activity
 *
 * Shows the full stats for a single recorded trip:
 * date, start/end times, duration, route names, distance,
 * top speed, average speed, mileage, and fuel consumed.
 *
 * Launched from {@link TripHistoryActivity} with a "trip_id" extra.
 */
public class TripDetailActivity extends AppCompatActivity {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault());
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("h:mm a", Locale.getDefault());

    private Realm realm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trip_detail);

        Toolbar toolbar = findViewById(R.id.toolbar_trip_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        String tripId = getIntent().getStringExtra("trip_id");
        if (tripId == null) {
            finish();
            return;
        }

        realm = Realm.getDefaultInstance();
        TripRecord trip = realm.copyFromRealm(
                realm.where(TripRecord.class).equalTo("id", tripId).findFirst());

        if (trip == null) {
            finish();
            return;
        }

        bindTrip(trip);
    }

    private void bindTrip(TripRecord trip) {
        // Date
        ((TextView) findViewById(R.id.tv_detail_date))
                .setText(DATE_FORMAT.format(new Date(trip.getStartTimeMs())));

        // Start / End times
        ((TextView) findViewById(R.id.tv_detail_start_time))
                .setText(TIME_FORMAT.format(new Date(trip.getStartTimeMs())));
        ((TextView) findViewById(R.id.tv_detail_end_time))
                .setText(trip.getEndTimeMs() > 0
                        ? TIME_FORMAT.format(new Date(trip.getEndTimeMs()))
                        : "—");

        // Duration
        long durationMs = trip.getDurationMs();
        String durationText;
        if (durationMs > 0) {
            long mins = TimeUnit.MILLISECONDS.toMinutes(durationMs);
            long hrs = mins / 60;
            long remainMins = mins % 60;
            durationText = hrs > 0
                    ? String.format(Locale.getDefault(), "%d hr %d min", hrs, remainMins)
                    : String.format(Locale.getDefault(), "%d min", mins);
        } else {
            durationText = "In Progress";
        }
        ((TextView) findViewById(R.id.tv_detail_duration)).setText(durationText);

        // Route
        ((TextView) findViewById(R.id.tv_detail_start_place))
                .setText(trip.getStartPlaceName() != null ? trip.getStartPlaceName() : "Start");
        ((TextView) findViewById(R.id.tv_detail_end_place))
                .setText(trip.getEndPlaceName() != null ? trip.getEndPlaceName() : "Destination");

        // Stats
        ((TextView) findViewById(R.id.tv_detail_distance))
                .setText(String.format(Locale.getDefault(), "%.1f km", trip.getDistanceKm()));
        ((TextView) findViewById(R.id.tv_detail_top_speed))
                .setText(String.format(Locale.getDefault(), "%d km/h", trip.getTopSpeedKmh()));
        ((TextView) findViewById(R.id.tv_detail_avg_speed))
                .setText(String.format(Locale.getDefault(), "%.0f km/h", trip.getAvgSpeedKmh()));

        float mileage = trip.getMileageKmL();
        ((TextView) findViewById(R.id.tv_detail_mileage))
                .setText(mileage > 0
                        ? String.format(Locale.getDefault(), "%.1f km/L", mileage)
                        : "—");

        ((TextView) findViewById(R.id.tv_detail_fuel))
                .setText(trip.getFuelConsumedLitres() > 0
                        ? String.format(Locale.getDefault(), "%.2f L", trip.getFuelConsumedLitres())
                        : "—");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (realm != null && !realm.isClosed()) realm.close();
    }
}
