package com.suzuki.sconnect.ble.protocol;

import com.suzuki.sconnect.utils.DebugLogger;

import java.nio.charset.StandardCharsets;

/**
 * Packet parser with validation and debug logging
 */
public class SuzukiPacketParser {

    public static class VehicleData {
        public int odometer; // in km
        public float tripA; // in km (with decimal)
        public float tripB; // in km (with decimal)
        public char gear; // 'N', '1', '2', etc.
        public int fuelLevel; // 1-6 bars
        public boolean isValid;

        @Override
        public String toString() {
            return String.format("ODO: %d km, Trip A: %.1f km, Trip B: %.1f km, Gear: %c, Fuel: %d bars",
                    odometer, tripA, tripB, gear, fuelLevel);
        }
    }

    /**
     * Parse Vehicle Status Packet (?7)
     */
    public static VehicleData parseVehicleStatusPacket(byte[] packet) {
        DebugLogger.i("PacketParser", "Parsing ?7 vehicle status packet");
        DebugLogger.logPacket("PacketParser", "Received packet", packet);

        VehicleData data = new VehicleData();

        // Validate packet structure
        if (packet == null || packet.length != 30) {
            DebugLogger.e("PacketParser", "Invalid packet length: " + (packet == null ? "null" : packet.length));
            data.isValid = false;
            return data;
        }

        if (packet[0] != (byte) 0xA5) {
            DebugLogger.e("PacketParser", String.format("Invalid header: 0x%02X (expected 0xA5)", packet[0]));
            data.isValid = false;
            return data;
        }

        if (packet[29] != (byte) 0x7F) {
            DebugLogger.e("PacketParser", String.format("Invalid footer: 0x%02X (expected 0x7F)", packet[29]));
            data.isValid = false;
            return data;
        }

        if (packet[1] != '7') {
            DebugLogger.w("PacketParser",
                    String.format("Not a ?7 packet, type is: %c (0x%02X)", (char) packet[1], packet[1]));
            data.isValid = false;
            return data;
        }

        try {
            // Extract ODO (bytes 5-10): 6-digit ASCII numeric string
            String odoStr = new String(packet, 5, 6, StandardCharsets.UTF_8);
            data.odometer = Integer.parseInt(odoStr.trim());
            DebugLogger.d("PacketParser", "  ODO: '" + odoStr + "' → " + data.odometer + " km");

            // Extract Trip A (bytes 11-16): 6-digit ASCII, last digit is decimal
            String tripAStr = new String(packet, 11, 6, StandardCharsets.UTF_8);
            data.tripA = Integer.parseInt(tripAStr.trim()) / 10.0f;
            DebugLogger.d("PacketParser", "  Trip A: '" + tripAStr + "' → " + data.tripA + " km");

            // Extract Trip B (bytes 17-22): 6-digit ASCII, last digit is decimal
            String tripBStr = new String(packet, 17, 6, StandardCharsets.UTF_8);
            data.tripB = Integer.parseInt(tripBStr.trim()) / 10.0f;
            DebugLogger.d("PacketParser", "  Trip B: '" + tripBStr + "' → " + data.tripB + " km");

            // Extract Gear (byte 23): ASCII character
            data.gear = (char) packet[23];
            DebugLogger.d("PacketParser", "  Gear: '" + data.gear + "'");

            // Extract Fuel Level (byte 24): ASCII '1' to '6'
            data.fuelLevel = packet[24] - '0';
            DebugLogger.d("PacketParser", "  Fuel: " + data.fuelLevel + " bars");

            // Log checksum
            DebugLogger.d("PacketParser", String.format("  Checksum: 0x%02X", packet[28]));

            data.isValid = true;
            DebugLogger.i("PacketParser", "✓ Successfully parsed vehicle data: " + data.toString());

        } catch (Exception e) {
            DebugLogger.e("PacketParser", "Error parsing vehicle data", e);
            data.isValid = false;
        }

        return data;
    }

    /**
     * Validate checksum of received packet
     */
    public static boolean validateChecksum(byte[] packet, boolean usesInvertedChecksum) {
        if (packet == null || packet.length != 30) {
            DebugLogger.e("ChecksumValidator", "Invalid packet for checksum validation");
            return false;
        }

        byte expectedChecksum = SuzukiPacketBuilder.calculateChecksum(packet, usesInvertedChecksum);
        byte actualChecksum = packet[28];

        boolean valid = actualChecksum == expectedChecksum;

        if (valid) {
            DebugLogger.d("ChecksumValidator", String.format("✓ Checksum valid: 0x%02X", actualChecksum));
        } else {
            DebugLogger.e("ChecksumValidator", String.format("✗ Checksum mismatch! Expected: 0x%02X, Got: 0x%02X",
                    expectedChecksum, actualChecksum));
        }

        return valid;
    }
}
