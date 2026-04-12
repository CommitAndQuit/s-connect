package com.suzuki.sconnect.ui.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.transition.TransitionSet;
import android.transition.ChangeBounds;
import android.transition.ChangeTransform;
import android.transition.ChangeClipBounds;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.suzuki.sconnect.R;
import com.suzuki.sconnect.ble.BleConnectionService;
import com.suzuki.sconnect.utils.VehicleStateHolder;

public class VehicleDetailsActivity extends AppCompatActivity {

    private TextView tvOdoValue, tvFuelValue, tvGearValue, tvTripAValue, tvTripBValue, tvSpeedValue, tvMileageValue;
    private ImageButton btnClose;
    private MaterialCardView btnLastParked, btnFuelEconomy, btnTripHistory, btnService;

    private BroadcastReceiver vehicleDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleConnectionService.ACTION_VEHICLE_DATA.equals(action)) {
                int speed = intent.getIntExtra("speed", 0);
                int odo = intent.getIntExtra("odometer", 0);
                float tripA = intent.getFloatExtra("tripA", 0);
                float tripB = intent.getFloatExtra("tripB", 0);
                char gear = (char) intent.getIntExtra("gear", 'N');
                int fuel = intent.getIntExtra("fuelLevel", 0);
                float mileageKmL = intent.getFloatExtra("mileageKmL", 0f);

                updateVehicleData(speed, odo, tripA, tripB, gear, fuel, mileageKmL);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable shared element transitions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setEnterTransition(new android.transition.Fade());
            
            TransitionSet transitionSet = new TransitionSet();
            transitionSet.addTransition(new ChangeBounds());
            transitionSet.addTransition(new ChangeTransform());
            transitionSet.addTransition(new ChangeClipBounds());
            transitionSet.setDuration(300);
            
            getWindow().setSharedElementEnterTransition(transitionSet);
            getWindow().setSharedElementReturnTransition(transitionSet);
            
            postponeEnterTransition();
        }

        setContentView(R.layout.activity_vehicle_details);

        initializeViews();
        setupClickListeners();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final View decor = getWindow().getDecorView();
            decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    decor.getViewTreeObserver().removeOnPreDrawListener(this);
                    startPostponedEnterTransition();
                    return true;
                }
            });
        }
    }

    private void initializeViews() {
        tvOdoValue = findViewById(R.id.tvOdoValueDetail);
        tvFuelValue = findViewById(R.id.tvFuelValueDetail);
        tvGearValue = findViewById(R.id.tvGearValueDetail);
        tvTripAValue = findViewById(R.id.tvTripAValueDetail);
        tvTripBValue = findViewById(R.id.tvTripBValueDetail);
        tvSpeedValue = findViewById(R.id.tvSpeedValueDetail);
        tvMileageValue = findViewById(R.id.tvMileageValueDetail);

        btnClose = findViewById(R.id.btnClose);
        btnLastParked = findViewById(R.id.btnLastParkedDetail);
        btnFuelEconomy = findViewById(R.id.btnFuelEconomyDetail);
        btnTripHistory = findViewById(R.id.btnTripHistoryDetail);
        btnService = findViewById(R.id.btnServiceDetail);
    }

    private void setupClickListeners() {
        btnClose.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAfterTransition();
            } else {
                finish();
            }
        });

        btnLastParked.setOnClickListener(v -> 
            startActivity(new Intent(this, LastParkedLocationActivity.class)));
        
        btnFuelEconomy.setOnClickListener(v -> 
            startActivity(new Intent(this, FuelEconomyActivity.class)));
        
        btnTripHistory.setOnClickListener(v -> 
            startActivity(new Intent(this, TripHistoryActivity.class)));
        
        btnService.setOnClickListener(v -> 
            startActivity(new Intent(this, ServiceRemindersActivity.class)));
    }

    private void updateVehicleData(int speed, int odo, float tripA, float tripB, char gear, int fuel, float mileageKmL) {
        runOnUiThread(() -> {
            if (tvSpeedValue != null)
                tvSpeedValue.setText(String.format("%d km/h", speed));
            if (tvOdoValue != null)
                tvOdoValue.setText(String.format("%,d", odo));

            int fuelPercent = Math.min(100, Math.round((fuel / 6.0f) * 100));
            if (tvFuelValue != null)
                tvFuelValue.setText(String.format("%d%%", fuelPercent));

            if (tvGearValue != null)
                tvGearValue.setText(String.valueOf(gear));
            if (tvTripAValue != null)
                tvTripAValue.setText(String.format("%.1f km", tripA));
            if (tvTripBValue != null)
                tvTripBValue.setText(String.format("%.1f km", tripB));

            if (tvMileageValue != null) {
                tvMileageValue.setText(mileageKmL > 0
                        ? String.format("%.1f", mileageKmL)
                        : "--");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleConnectionService.ACTION_VEHICLE_DATA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vehicleDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vehicleDataReceiver, filter);
        }

        // Load initial state if available
        VehicleStateHolder state = VehicleStateHolder.getInstance();
        state.loadState(this);
        if (state.hasData()) {
            updateVehicleData(
                    state.getSpeed(),
                    state.getOdometer(),
                    state.getTripA(),
                    state.getTripB(),
                    state.getGear(),
                    state.getFuelLevel(),
                    state.getMileageKmL()
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(vehicleDataReceiver);
    }
}
