package com.suzuki.sconnect.ble.protocol;

public class SuzukiProtocol implements VehicleProtocol {

    @Override
    public boolean usesInvertedChecksum(String deviceName) {
        return SuzukiPacketBuilder.usesInvertedChecksum(deviceName);
    }

    @Override
    public byte[] buildIdentificationPacket(String userName, boolean isNewConnection, boolean usesInvertedChecksum) {
        return SuzukiPacketBuilder.buildIdentificationPacket(userName, isNewConnection, usesInvertedChecksum);
    }

    @Override
    public byte[] buildHeartbeatPacket(String batteryStatus, String speedStr, String signalLevel, String time, boolean usesInvertedChecksum) {
        return SuzukiPacketBuilder.buildHeartbeatPacket(batteryStatus, speedStr, signalLevel, time, usesInvertedChecksum);
    }

    @Override
    public byte[] buildCallPacket(String name, int status, boolean usesInvertedChecksum) {
        return SuzukiPacketBuilder.buildCallPacket(name, status, usesInvertedChecksum);
    }

    @Override
    public byte[] buildMissedCallPacket(String name, int count, boolean usesInvertedChecksum) {
        return SuzukiPacketBuilder.buildMissedCallPacket(name, count, usesInvertedChecksum);
    }

    @Override
    public byte[] buildNotificationPacket(String title, int count, int type, boolean usesInvertedChecksum) {
        return SuzukiPacketBuilder.buildNotificationPacket(title, count, type, usesInvertedChecksum);
    }

    @Override
    public byte[] buildNavigationPacket(int distanceMeters, int totalDistanceRemaining, int turnIconId,
            String etaStr, String statusCode, boolean usesInvertedChecksum) {
        return SuzukiPacketBuilder.buildNavigationPacket(distanceMeters, totalDistanceRemaining, turnIconId, etaStr, statusCode, usesInvertedChecksum);
    }

    @Override
    public VehicleData parseVehicleStatusPacket(byte[] packet) {
        // Need to refactor SuzukiPacketParser to return the new VehicleData
        return SuzukiPacketParser.parseVehicleStatusPacketGeneric(packet);
    }

    @Override
    public boolean validateChecksum(byte[] packet, boolean usesInvertedChecksum) {
        return SuzukiPacketParser.validateChecksum(packet, usesInvertedChecksum);
    }

    @Override
    public boolean isDeviceSupported(String name) {
        return name != null && (name.contains("_AS") ||
                name.contains("_BS") ||
                name.contains("_SBM") ||
                name.contains("AS") ||
                name.contains("BS") ||
                name.contains("SBM") ||
                name.startsWith("A") ||
                name.startsWith("B"));
    }
}
