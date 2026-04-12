package com.suzuki.sconnect.utils;

import android.util.Log;

/**
 * Centralized logging utility.
 */
public class DebugLogger {
    private static final String TAG = "SConnectBLE";

    // Debug logging methods
    public static void d(String tag, String message) {
        Log.d(TAG, tag + ": " + message);
    }

    public static void i(String tag, String message) {
        Log.i(TAG, tag + ": " + message);
    }

    public static void w(String tag, String message) {
        Log.w(TAG, tag + ": " + message);
    }

    public static void e(String tag, String message) {
        Log.e(TAG, tag + ": " + message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        Log.e(TAG, tag + ": " + message, throwable);
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
        for (byte b : packet) {
            hex.append(String.format("%02X ", b));
        }

        i(tag, String.format("%s [%d bytes] HEX: %s", direction, packet.length, hex.toString().trim()));
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
