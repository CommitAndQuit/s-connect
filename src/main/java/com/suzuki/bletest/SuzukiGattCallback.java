package com.suzuki.bletest;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import java.util.List;
import java.util.UUID;

/**
 * GATT Callback with comprehensive state machine logging
 */
public class SuzukiGattCallback extends BluetoothGattCallback {
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MTU_SIZE = 250;

    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private ConnectionListener listener;
    private boolean usesInvertedChecksum;
    private String currentState = "DISCONNECTED";

    public interface ConnectionListener {
        void onConnected();

        void onDisconnected();

        void onReady(); // Called when ready to send/receive data

        void onVehicleDataReceived(SuzukiPacketParser.VehicleData data);

        void onError(String error);
    }

    public SuzukiGattCallback(ConnectionListener listener, boolean usesInvertedChecksum) {
        this.listener = listener;
        this.usesInvertedChecksum = usesInvertedChecksum;
        DebugLogger.i("GattCallback",
                "Created with checksum type: " + (usesInvertedChecksum ? "INVERTED (255-sum)" : "DIRECT (sum)"));
    }

    private void setState(String newState) {
        DebugLogger.logStateChange(currentState, newState);
        currentState = newState;
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        DebugLogger.i("GATT", "=== onConnectionStateChange ===");
        DebugLogger.d("GATT", "  Status: " + status + " (" + getGattStatusName(status) + ")");
        DebugLogger.d("GATT", "  New State: " + newState + " (" + getConnectionStateName(newState) + ")");

        if (newState == BluetoothGatt.STATE_CONNECTED) {
            setState("CONNECTED");
            DebugLogger.i("GATT", "✓ Connected to GATT server");
            listener.onConnected();

            // Discover services
            DebugLogger.logGattOp("discoverServices", "Starting service discovery...");
            try {
                boolean result = gatt.discoverServices();
                DebugLogger.d("GATT", "  discoverServices() returned: " + result);
            } catch (SecurityException e) {
                DebugLogger.e("GATT", "Permission denied for service discovery", e);
                listener.onError("Permission denied");
            }

        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            setState("DISCONNECTED");
            DebugLogger.w("GATT", "Disconnected from GATT server");
            listener.onDisconnected();
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        DebugLogger.i("GATT", "=== onServicesDiscovered ===");
        DebugLogger.d("GATT", "  Status: " + status + " (" + getGattStatusName(status) + ")");

        if (status == BluetoothGatt.GATT_SUCCESS) {
            setState("SERVICES_DISCOVERED");

            // Log all services and characteristics
            List<BluetoothGattService> services = gatt.getServices();
            DebugLogger.i("GATT", "Found " + services.size() + " services:");

            for (int i = 0; i < services.size(); i++) {
                BluetoothGattService service = services.get(i);
                DebugLogger.d("GATT", String.format("  [%d] Service UUID: %s", i, service.getUuid()));

                List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                for (int j = 0; j < characteristics.size(); j++) {
                    BluetoothGattCharacteristic characteristic = characteristics.get(j);
                    DebugLogger.d("GATT", String.format("      [%d] Char UUID: %s (Properties: 0x%02X)",
                            j, characteristic.getUuid(), characteristic.getProperties()));
                }
            }

            try {
                // Access Service[3], Characteristic[0] for writes, Characteristic[1] for
                // notifications
                if (services.size() <= 3) {
                    String error = "Not enough services! Expected at least 4, found " + services.size();
                    DebugLogger.e("GATT", error);
                    listener.onError(error);
                    return;
                }

                BluetoothGattService service = services.get(3);
                DebugLogger.i("GATT", "Using Service[3]: " + service.getUuid());

                List<BluetoothGattCharacteristic> chars = service.getCharacteristics();
                if (chars.size() < 2) {
                    String error = "Service[3] doesn't have enough characteristics! Expected 2, found " + chars.size();
                    DebugLogger.e("GATT", error);
                    listener.onError(error);
                    return;
                }

                writeCharacteristic = chars.get(0);
                notifyCharacteristic = chars.get(1);

                DebugLogger.i("GATT", "Write Characteristic[0]: " + writeCharacteristic.getUuid());
                DebugLogger.i("GATT", "Notify Characteristic[1]: " + notifyCharacteristic.getUuid());

                // Request MTU = 250
                DebugLogger.logGattOp("requestMtu", "Requesting MTU = " + MTU_SIZE);
                boolean result = gatt.requestMtu(MTU_SIZE);
                DebugLogger.d("GATT", "  requestMtu() returned: " + result);

            } catch (IndexOutOfBoundsException e) {
                String error = "Service or characteristic not found at expected index";
                DebugLogger.e("GATT", error, e);
                listener.onError(error);
            }
        } else {
            String error = "Service discovery failed with status: " + status;
            DebugLogger.e("GATT", error);
            listener.onError(error);
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        DebugLogger.i("GATT", "=== onMtuChanged ===");
        DebugLogger.d("GATT", "  MTU: " + mtu);
        DebugLogger.d("GATT", "  Status: " + status + " (" + getGattStatusName(status) + ")");

        if (status == BluetoothGatt.GATT_SUCCESS) {
            setState("MTU_CHANGED");
            DebugLogger.i("GATT", "✓ MTU changed to: " + mtu);

            // Enable notifications
            enableNotifications(gatt);
        } else {
            String error = "MTU change failed with status: " + status;
            DebugLogger.e("GATT", error);
            listener.onError(error);
        }
    }

    private void enableNotifications(BluetoothGatt gatt) {
        // Check if characteristic was properly initialized
        if (notifyCharacteristic == null) {
            String error = "Cannot enable notifications: notifyCharacteristic is null!";
            DebugLogger.e("GATT", error);
            listener.onError(error);
            return;
        }

        DebugLogger.logGattOp("enableNotifications", "Enabling notifications on " + notifyCharacteristic.getUuid());

        try {
            // Enable local notification
            boolean result = gatt.setCharacteristicNotification(notifyCharacteristic, true);
            DebugLogger.d("GATT", "  setCharacteristicNotification() returned: " + result);

            // Write to CCCD descriptor
            BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                DebugLogger.d("GATT", "  Found CCCD descriptor: " + CCCD_UUID);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean writeResult = gatt.writeDescriptor(descriptor);
                DebugLogger.d("GATT", "  writeDescriptor() returned: " + writeResult);
            } else {
                String error = "CCCD descriptor not found!";
                DebugLogger.e("GATT", error);
                listener.onError(error);
            }
        } catch (SecurityException e) {
            DebugLogger.e("GATT", "Permission denied for enabling notifications", e);
            listener.onError("Permission denied");
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        DebugLogger.i("GATT", "=== onDescriptorWrite ===");
        DebugLogger.d("GATT", "  Descriptor: " + descriptor.getUuid());
        DebugLogger.d("GATT", "  Status: " + status + " (" + getGattStatusName(status) + ")");

        if (status == BluetoothGatt.GATT_SUCCESS) {
            setState("NOTIFICATIONS_ENABLED");
            DebugLogger.i("GATT", "✓ Notifications enabled successfully");
            DebugLogger.i("GATT", "=== CONNECTION READY ===");
            listener.onReady();
        } else {
            String error = "Failed to enable notifications, status: " + status;
            DebugLogger.e("GATT", error);
            listener.onError(error);
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        byte[] data = characteristic.getValue();

        DebugLogger.i("GATT", "=== onCharacteristicChanged ===");
        DebugLogger.logPacket("GATT", "← Received from vehicle", data);

        // Parse vehicle data
        SuzukiPacketParser.VehicleData vehicleData = SuzukiPacketParser.parseVehicleStatusPacket(data);

        if (vehicleData.isValid) {
            // Validate checksum
            boolean checksumValid = SuzukiPacketParser.validateChecksum(data, usesInvertedChecksum);
            if (checksumValid) {
                listener.onVehicleDataReceived(vehicleData);
            } else {
                DebugLogger.w("GATT", "Received valid packet but checksum failed");
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        DebugLogger.i("GATT", "=== onCharacteristicWrite ===");
        DebugLogger.d("GATT", "  Characteristic: " + characteristic.getUuid());
        DebugLogger.d("GATT", "  Status: " + status + " (" + getGattStatusName(status) + ")");

        if (status == BluetoothGatt.GATT_SUCCESS) {
            DebugLogger.i("GATT", "✓ Write successful");
        } else {
            DebugLogger.e("GATT", "✗ Write failed with status: " + status);
        }
    }

    public void writePacket(BluetoothGatt gatt, byte[] packet) {
        if (writeCharacteristic != null) {
            DebugLogger.logPacket("GATT", "→ Sending to vehicle", packet);
            writeCharacteristic.setValue(packet);
            try {
                boolean result = gatt.writeCharacteristic(writeCharacteristic);
                DebugLogger.d("GATT", "  writeCharacteristic() returned: " + result);
            } catch (SecurityException e) {
                DebugLogger.e("GATT", "Permission denied for write", e);
            }
        } else {
            DebugLogger.e("GATT", "Cannot write: writeCharacteristic is null!");
        }
    }

    private String getGattStatusName(int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
                return "SUCCESS";
            case BluetoothGatt.GATT_FAILURE:
                return "FAILURE";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                return "INSUFFICIENT_AUTHENTICATION";
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                return "INSUFFICIENT_ENCRYPTION";
            case BluetoothGatt.GATT_INVALID_OFFSET:
                return "INVALID_OFFSET";
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                return "READ_NOT_PERMITTED";
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
                return "REQUEST_NOT_SUPPORTED";
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                return "WRITE_NOT_PERMITTED";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }

    private String getConnectionStateName(int state) {
        switch (state) {
            case BluetoothGatt.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothGatt.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothGatt.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothGatt.STATE_DISCONNECTING:
                return "DISCONNECTING";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    public String getCurrentState() {
        return currentState;
    }
}
