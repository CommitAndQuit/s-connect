package com.suzuki.sconnect.ble.protocol;

import com.suzuki.sconnect.utils.DebugLogger;

import java.nio.charset.StandardCharsets;

/**
 * Packet parser with validation and debug logging
 */
public class SuzukiPacketParser {

    public static class VehicleData {
        public int speed; // in km/h (bytes 2-4)
        public int odometer; // in km
        public float tripA; // in km (with decimal)
        public float tripB; // in km (with decimal)
        public char gear; // 'N', '1', '2', etc.
        public int fuelLevel; // 1-6 bars
        public boolean isValid;

        @Override
        public String toString() {
            return String.format(
                    "Speed: %d km/h, ODO: %d km, Trip A: %.1f km, Trip B: %.1f km, Gear: %c, Fuel: %d bars",
                    speed, odometer, tripA, tripB, gear, fuelLevel);
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
            // Extract Speed (bytes 2-4): 3-digit ASCII numeric string in km/h
            String speedStr = new String(packet, 2, 3, StandardCharsets.UTF_8);
            data.speed = Integer.parseInt(speedStr.trim());
            DebugLogger.d("PacketParser", "  Speed: '" + speedStr + "' → " + data.speed + " km/h");

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

            // Extract Gear (byte 23): Could be binary (0-6) or ASCII ('N', '1'-'6')
            byte gearByte = packet[23];
            DebugLogger.d("PacketParser", String.format("  Raw Gear Byte: 0x%02X (%d)", gearByte, gearByte & 0xFF));

            if (gearByte >= 0 && gearByte <= 6) {
                // Binary detected: 0=N, 1=Gear 1, ..., 6=Gear 6
                data.gear = (gearByte == 0) ? 'N' : (char) ('0' + gearByte);
                DebugLogger.d("PacketParser", "  → Decoded from BINARY: '" + data.gear + "'");
            } else if (gearByte == 'N' || (gearByte >= '1' && gearByte <= '6')) {
                // ASCII detected: 'N' (0x4E), '1' (0x31), etc.
                data.gear = (char) gearByte;
                DebugLogger.d("PacketParser", "  → Decoded from ASCII: '" + data.gear + "'");
            } else {
                // Fallback for unknown values
                data.gear = '-';
                DebugLogger.w("PacketParser", String.format("  → Unknown gear value: 0x%02X", gearByte));
            }

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
