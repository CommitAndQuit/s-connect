package com.suzuki.sconnect.ui.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.ServiceReminderManager;
import com.suzuki.sconnect.data.model.ServiceReminder;
import com.suzuki.sconnect.ui.adapters.ServiceReminderAdapter;

import java.util.List;

import io.realm.Realm;
import io.realm.RealmResults;

public class ServiceRemindersActivity extends AppCompatActivity {

    private RecyclerView rvReminders;
    private ServiceReminderAdapter adapter;
    private TextView tvHealthStatus, tvOdoDisplay;
    private ImageView ivHealthIndicator;
    private Realm realm;
    private int currentOdometer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_reminders);

        realm = Realm.getDefaultInstance();
        
        // Get last known ODO
        SharedPreferences prefs = getSharedPreferences("SConnectPrefs", MODE_PRIVATE);
        currentOdometer = prefs.getInt("last_odometer", 0);

        initializeUI();
        loadReminders();
    }

    private void initializeUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Maintenance");
        }

        tvHealthStatus = findViewById(R.id.tvHealthStatus);
        tvOdoDisplay = findViewById(R.id.tvOdoDisplay);
        ivHealthIndicator = findViewById(R.id.ivHealthIndicator);
        rvReminders = findViewById(R.id.rvServiceReminders);

        tvOdoDisplay.setText(String.format("Current ODO: %,d km", currentOdometer));

        rvReminders.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadReminders() {
        RealmResults<ServiceReminder> results = realm.where(ServiceReminder.class).findAll();
        List<ServiceReminder> reminders = realm.copyFromRealm(results);

        if (adapter == null) {
            adapter = new ServiceReminderAdapter(reminders, currentOdometer);
            rvReminders.setAdapter(adapter);
        } else {
            adapter.updateData(reminders, currentOdometer);
        }

        updateHealthHeader();
    }

    private void updateHealthHeader() {
        int dueCount = ServiceReminderManager.getDueRemindersCount(currentOdometer);
        if (dueCount > 0) {
            tvHealthStatus.setText(dueCount + " tasks need attention");
            ivHealthIndicator.setImageResource(R.drawable.ic_warning); // Assuming ic_warning exists or use system one
            ivHealthIndicator.setColorFilter(android.graphics.Color.parseColor("#EF4444"));
        } else {
            tvHealthStatus.setText("Everything looks healthy");
            ivHealthIndicator.setImageResource(R.drawable.ic_check_circle);
            ivHealthIndicator.setColorFilter(android.graphics.Color.parseColor("#10B981"));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadReminders(); // Refresh list when returning from edit
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (realm != null) {
            realm.close();
        }
    }
}
