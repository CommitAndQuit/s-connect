package com.suzuki.bletest;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Centralized debug logging utility with in-memory log storage
 * for display in the debug UI
 */
public class DebugLogger {
    private static final String TAG = "SuzukiBLE";
    private static final int MAX_LOGS = 500;
    private static final List<LogEntry> logs = new ArrayList<>();
    private static LogListener listener;

    public static class LogEntry {
        public final String timestamp;
        public final String level;
        public final String tag;
        public final String message;

        public LogEntry(String level, String tag, String message) {
            this.timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s/%s: %s", timestamp, level, tag, message);
        }
    }

    public interface LogListener {
        void onNewLog(LogEntry entry);
    }

    public static void setListener(LogListener l) {
        listener = l;
    }

    private static void addLog(LogEntry entry) {
        synchronized (logs) {
            logs.add(entry);
            if (logs.size() > MAX_LOGS) {
                logs.remove(0);
            }
        }
        if (listener != null) {
            listener.onNewLog(entry);
        }
    }

    public static List<LogEntry> getAllLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public static void clearLogs() {
        synchronized (logs) {
            logs.clear();
        }
    }

    // Debug logging methods
    public static void d(String tag, String message) {
        Log.d(TAG, tag + ": " + message);
        addLog(new LogEntry("D", tag, message));
    }

    public static void i(String tag, String message) {
        Log.i(TAG, tag + ": " + message);
        addLog(new LogEntry("I", tag, message));
    }

    public static void w(String tag, String message) {
        Log.w(TAG, tag + ": " + message);
        addLog(new LogEntry("W", tag, message));
    }

    public static void e(String tag, String message) {
        Log.e(TAG, tag + ": " + message);
        addLog(new LogEntry("E", tag, message));
    }

    public static void e(String tag, String message, Throwable throwable) {
        Log.e(TAG, tag + ": " + message, throwable);
        addLog(new LogEntry("E", tag, message + " | " + throwable.getMessage()));
    }

    /**
     * Log a byte array as hex string
     */
    public static void logPacket(String tag, String direction, byte[] packet) {
        if (packet == null) {
            e(tag, direction + " packet is null");
            return;
        }

        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();

        for (int i = 0; i < packet.length; i++) {
            hex.append(String.format("%02X ", packet[i]));
            char c = (char) (packet[i] & 0xFF);
            ascii.append((c >= 32 && c < 127) ? c : '.');

            if ((i + 1) % 10 == 0) {
                hex.append("\n                ");
                ascii.append("\n                ");
            }
        }

        String message = String.format("%s [%d bytes]\nHEX:  %s\nASCII: %s",
                direction, packet.length, hex.toString().trim(), ascii.toString().trim());

        i(tag, message);
    }

    /**
     * Log connection state change
     */
    public static void logStateChange(String from, String to) {
        i("STATE", String.format("Transition: %s → %s", from, to));
    }

    /**
     * Log GATT operation
     */
    public static void logGattOp(String operation, String details) {
        d("GATT", String.format("%s: %s", operation, details));
    }
}
