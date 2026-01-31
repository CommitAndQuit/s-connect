package com.suzuki.sconnect;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class DebugActivity extends AppCompatActivity {

    private ListView lvLogs;
    private ArrayAdapter<String> logAdapter;
    private List<String> logList = new ArrayList<>();
    private Button btnClearLogs;
    private ImageButton btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        lvLogs = findViewById(R.id.lvLogsDebug);
        btnClearLogs = findViewById(R.id.btnClearLogsDebug);
        btnBack = findViewById(R.id.btnBackDebug);

        logAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, logList);
        lvLogs.setAdapter(logAdapter);

        btnBack.setOnClickListener(v -> finish());
        btnClearLogs.setOnClickListener(v -> {
            DebugLogger.clearLogs();
            logList.clear();
            logAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        setupLogListener();
    }

    private void setupLogListener() {
        DebugLogger.setListener(entry -> runOnUiThread(() -> {
            logList.add(entry.toString());
            if (logList.size() > 500) {
                logList.remove(0);
            }
            logAdapter.notifyDataSetChanged();
            lvLogs.setSelection(logList.size() - 1);
        }));

        // Load existing logs
        for (DebugLogger.LogEntry entry : DebugLogger.getAllLogs()) {
            logList.add(entry.toString());
        }
        logAdapter.notifyDataSetChanged();
        lvLogs.setSelection(logList.size() - 1);
    }
}
