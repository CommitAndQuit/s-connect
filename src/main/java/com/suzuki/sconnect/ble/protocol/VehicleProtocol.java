package com.suzuki.sconnect.ble.protocol;

public interface VehicleProtocol {

    // Checksum/config related
    boolean usesInvertedChecksum(String deviceName);

    // Packet Builders
    byte[] buildIdentificationPacket(String userName, boolean isNewConnection, boolean usesInvertedChecksum);
    byte[] buildHeartbeatPacket(String batteryStatus, String speedStr, String signalLevel, String time, boolean usesInvertedChecksum);
    byte[] buildCallPacket(String name, int status, boolean usesInvertedChecksum);
    byte[] buildMissedCallPacket(String name, int count, boolean usesInvertedChecksum);
    byte[] buildNotificationPacket(String title, int count, int type, boolean usesInvertedChecksum);
    byte[] buildNavigationPacket(int distanceMeters, int totalDistanceRemaining, int turnIconId,
            String etaStr, String statusCode, boolean usesInvertedChecksum);

    // Packet Parsers
    VehicleData parseVehicleStatusPacket(byte[] packet);

    // Validation
    boolean validateChecksum(byte[] packet, boolean usesInvertedChecksum);

    // Scanner
    boolean isDeviceSupported(String deviceName);
}
