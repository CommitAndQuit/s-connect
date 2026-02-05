package com.suzuki.sconnect.ui.activities;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.mappls.sdk.direction.ui.DirectionFragment;
import com.mappls.sdk.direction.ui.DirectionCallback;
import com.mappls.sdk.direction.ui.model.DirectionOptions;
import com.mappls.sdk.direction.ui.model.DirectionPoint;
import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.services.api.directions.DirectionsCriteria;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;

import java.util.Arrays;

public class NavigationActivity extends AppCompatActivity implements DirectionCallback {

    private DirectionFragment directionFragment;
    private double originLat, originLng, destLat, destLng;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);

        originLat = getIntent().getDoubleExtra("origin_lat", 0);
        originLng = getIntent().getDoubleExtra("origin_lng", 0);
        destLat = getIntent().getDoubleExtra("dest_lat", 0);
        destLng = getIntent().getDoubleExtra("dest_lng", 0);

        if (destLat == 0 || destLng == 0) {
            Toast.makeText(this, "Invalid destination", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupNavigation();
    }

    private void setupNavigation() {
        DirectionPoint origin = null;
        if (originLat != 0 && originLng != 0) {
            origin = DirectionPoint.setDirection(Point.fromLngLat(originLng, originLat), "Current Location", "");
        }

        DirectionPoint destination = DirectionPoint.setDirection(Point.fromLngLat(destLng, destLat), "Destination", "");

        DirectionOptions.Builder builder = DirectionOptions.builder()
                .showAlternative(true)
                .steps(true)
                .annotation(
                        Arrays.asList(DirectionsCriteria.ANNOTATION_CONGESTION, DirectionsCriteria.ANNOTATION_DURATION))
                .destination(destination);

        if (origin != null) {
            builder.origin(origin);
        }

        directionFragment = DirectionFragment.newInstance(builder.build());
        directionFragment.setDirectionCallback(this);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.navigation_container, directionFragment, DirectionFragment.class.getSimpleName())
                .commit();
    }

    @Override
    public void onCancel() {
        finish();
    }

    @Override
    public void onStartNavigation(DirectionPoint origin, DirectionPoint destination,
            java.util.List<DirectionPoint> waypoints, DirectionsResponse directionsResponse, int index) {
        // Navigation started
        Toast.makeText(this, "Starting Navigation...", Toast.LENGTH_SHORT).show();
    }
}
