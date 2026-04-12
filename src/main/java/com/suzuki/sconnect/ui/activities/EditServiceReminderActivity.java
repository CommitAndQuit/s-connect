package com.suzuki.sconnect.ui.activities;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.ServiceReminder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import io.realm.Realm;

public class EditServiceReminderActivity extends AppCompatActivity {

    private EditText etLastOdo, etLastDate, etIntervalKm, etIntervalDays;
    private TextView tvItemName;
    private Button btnSave;
    private Realm realm;
    private String reminderId;
    private long selectedDateMs;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_service_reminder);

        realm = Realm.getDefaultInstance();
        reminderId = getIntent().getStringExtra("reminder_id");

        initializeUI();
        loadReminderData();
    }

    private void initializeUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvItemName = findViewById(R.id.tvEditItemName);
        etLastOdo = findViewById(R.id.etLastOdo);
        etLastDate = findViewById(R.id.etLastDate);
        etIntervalKm = findViewById(R.id.etIntervalKm);
        etIntervalDays = findViewById(R.id.etIntervalDays);
        btnSave = findViewById(R.id.btnSaveReminder);

        etLastDate.setOnClickListener(v -> showDatePicker());
        btnSave.setOnClickListener(v -> saveReminder());
    }

    private void loadReminderData() {
        ServiceReminder reminder = realm.where(ServiceReminder.class).equalTo("id", reminderId).findFirst();
        if (reminder != null) {
            tvItemName.setText(reminder.getName());
            etLastOdo.setText(String.valueOf(reminder.getLastServiceOdometer()));
            
            selectedDateMs = reminder.getLastServiceDate();
            etLastDate.setText(dateFormat.format(new Date(selectedDateMs)));
            
            etIntervalKm.setText(String.valueOf(reminder.getIntervalKm()));
            etIntervalDays.setText(String.valueOf(reminder.getIntervalDays()));
        }
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(selectedDateMs);
        
        DatePickerDialog datePickerDialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month);
            calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            
            selectedDateMs = calendar.getTimeInMillis();
            etLastDate.setText(dateFormat.format(calendar.getTime()));
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
        
        datePickerDialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        datePickerDialog.show();
    }

    private void saveReminder() {
        String lastOdoStr = etLastOdo.getText().toString();
        String intervalKmStr = etIntervalKm.getText().toString();
        String intervalDaysStr = etIntervalDays.getText().toString();

        if (lastOdoStr.isEmpty() || intervalKmStr.isEmpty() || intervalDaysStr.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        realm.executeTransaction(r -> {
            ServiceReminder reminder = r.where(ServiceReminder.class).equalTo("id", reminderId).findFirst();
            if (reminder != null) {
                reminder.setLastServiceOdometer(Integer.parseInt(lastOdoStr));
                reminder.setLastServiceDate(selectedDateMs);
                reminder.setIntervalKm(Integer.parseInt(intervalKmStr));
                reminder.setIntervalDays(Integer.parseInt(intervalDaysStr));
            }
        });

        Toast.makeText(this, "Reminder updated", Toast.LENGTH_SHORT).show();
        finish();
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
