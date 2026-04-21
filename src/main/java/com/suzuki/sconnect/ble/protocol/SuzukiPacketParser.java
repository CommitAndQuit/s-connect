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
        /** Instantaneous fuel consumption in litres (standard scooter/bike formula from bytes 25-27). */
        public float fuelConsumption; // litres per cluster update
        public boolean isValid;

        @Override
        public String toString() {
            return String.format(
                    "Speed: %d km/h, ODO: %d km, Trip A: %.1f km, Trip B: %.1f km, Gear: %c, Fuel: %d bars, FC: %.4f L",
                    speed, odometer, tripA, tripB, gear, fuelLevel, fuelConsumption);
        }
    }

    /**
     * Parse Vehicle Status Packet (?7) and return generic VehicleData
     */
    public static com.suzuki.sconnect.ble.protocol.VehicleData parseVehicleStatusPacketGeneric(byte[] packet) {
        VehicleData oldData = parseVehicleStatusPacket(packet);
        if (oldData == null) return null;
        com.suzuki.sconnect.ble.protocol.VehicleData newData = new com.suzuki.sconnect.ble.protocol.VehicleData();
        newData.speed = oldData.speed;
        newData.odometer = oldData.odometer;
        newData.tripA = oldData.tripA;
        newData.tripB = oldData.tripB;
        newData.gear = oldData.gear;
        newData.fuelLevel = oldData.fuelLevel;
        newData.fuelConsumption = oldData.fuelConsumption;
        newData.isValid = oldData.isValid;
        return newData;
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

            // Extract Gear (byte 23): unsigned value, binary (0-6) or ASCII ('N'=78, '1'=49..'6'=54)
            // BUG FIX: Use (gearByte & 0xFF) to treat as unsigned and avoid signed-byte range confusion.
            // Binary range 0–6 is unambiguous from ASCII range 49–78 with unsigned comparison.
            byte gearByte = packet[23];
            int gearUnsigned = gearByte & 0xFF;
            DebugLogger.d("PacketParser", String.format("  Raw Gear Byte: 0x%02X (unsigned=%d)", gearByte, gearUnsigned));

            if (gearUnsigned <= 6) {
                // Binary encoding: 0=Neutral, 1=1st, 2=2nd, ... 6=6th
                // IMPORTANT: binary 0 must map to 'N' (not '\0') so the char round-trips correctly
                // through Intent.putExtra("gear", (int)data.gear) → getIntExtra("gear", 'N').
                data.gear = (gearUnsigned == 0) ? 'N' : (char) ('0' + gearUnsigned);
                DebugLogger.d("PacketParser", "  → Decoded from BINARY: '" + data.gear + "'");
            } else if (gearUnsigned == 'N' || (gearUnsigned >= '1' && gearUnsigned <= '6')) {
                // ASCII encoding: 'N'=0x4E=78, '1'=0x31=49 ... '6'=0x36=54
                data.gear = (char) gearUnsigned;
                DebugLogger.d("PacketParser", "  → Decoded from ASCII: '" + data.gear + "'");
            } else {
                // Fallback for unexpected values
                data.gear = '-';
                DebugLogger.w("PacketParser", String.format("  → Unknown gear value: 0x%02X", gearByte));
            }

            // Extract Fuel Level (byte 24): ASCII '1' to '6'
            data.fuelLevel = packet[24] - '0';
            DebugLogger.d("PacketParser", "  Fuel: " + data.fuelLevel + " bars");

            // ── P3: Fuel Consumption (bytes 25-27) ──────────────────────────────────────────
            // Official app concatenates 8-bit binary strings of bytes 25, 26, 27 → 24-bit field.
            // Standard scooter/motorcycle formula (from HomeScreenActivity.onClusterDataRecev):
            //   bits[0..12]  = integer part × 10  (13 bits)
            //   bits[13..23] = fractional part / 2048  (11 bits)
            //   result = (intPart + fracPart / 2048.0) / 10.0  [litres per update]
            //
            // Note: e-ACCESS (EV) and Access-TFT Edition use different formulas. Since SConnect
            // has no vehicle-profile feature yet, the standard formula is used for all models.
            // This will be corrected when vehicle profiles (P8) are implemented.
            int b25 = packet[25] & 0xFF;
            int b26 = packet[26] & 0xFF;
            int b27 = packet[27] & 0xFF;

            // Combine into a 24-bit unsigned integer (MSB = byte25)
            int raw24 = (b25 << 16) | (b26 << 8) | b27;

            // Extract 13-bit integer part (bits 23..11) and 11-bit fractional part (bits 10..0)
            int intPart  = (raw24 >> 11) & 0x1FFF;  // top 13 bits
            int fracPart = raw24 & 0x7FF;             // bottom 11 bits

            data.fuelConsumption = (intPart + fracPart / 2048.0f) / 10.0f;
            DebugLogger.d("PacketParser", String.format(
                    "  Fuel Consumption: raw24=0x%06X, intPart=%d, fracPart=%d → %.4f L",
                    raw24, intPart, fracPart, data.fuelConsumption));
            // ────────────────────────────────────────────────────────────────────────────────

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
