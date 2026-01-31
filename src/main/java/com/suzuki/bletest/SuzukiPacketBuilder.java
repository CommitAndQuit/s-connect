package com.suzuki.bletest;

import java.nio.charset.StandardCharsets;

/**
 * Packet builder with extensive debug logging
 */
public class SuzukiPacketBuilder {
    private static final byte HEADER = (byte) 0xA5;
    private static final byte FOOTER = (byte) 0x7F;
    private static final int PACKET_SIZE = 30;

    /**
     * Construct Identification Packet (?6)
     * Sent ONCE after connection
     * 
     * @param userName             The user name to display on cluster
     * @param isNewConnection      true to send 'F' (Fast/First pairing), false to
     *                             send 'R' (Regular reconnection)
     * @param usesInvertedChecksum true for 'A' prefix models, false for 'B' prefix
     *                             models
     */
    public static byte[] buildIdentificationPacket(String userName, boolean isNewConnection,
            boolean usesInvertedChecksum) {
        DebugLogger.i("PacketBuilder", "Building ?6 identification packet");
        DebugLogger.d("PacketBuilder", "  Username: '" + userName + "'");
        DebugLogger.d("PacketBuilder", "  Type: " + (isNewConnection ? "New Connection (F)" : "Reconnection (R)"));
        DebugLogger.d("PacketBuilder", "  Checksum: " + (usesInvertedChecksum ? "INVERTED" : "DIRECT"));

        byte[] packet = new byte[PACKET_SIZE];

        try {
            // Pad username to 22 characters
            String originalName = userName;
            if (userName.length() > 22) {
                userName = userName.substring(0, 22);
                DebugLogger.w("PacketBuilder", "  Username truncated from " + originalName.length() + " to 22 chars");
            }
            while (userName.length() < 22) {
                userName += "\u0000";
            }

            // Build string: "?6" + userName + padding
            String payload = "?6" + userName + "\u0000\u0000\u0000\u0000\u0000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            System.arraycopy(payloadBytes, 0, packet, 0, payloadBytes.length);

            // Set header and footer
            packet[0] = HEADER;

            // Set padding bytes to 0xFF
            for (int i = 22; i <= 26; i++) {
                packet[i] = (byte) 0xFF;
            }

            // Model/Connection flag: 'F' (70) for New Check/Fast, 'R' (82) for
            // Regular/Reconnection
            packet[27] = isNewConnection ? (byte) 70 : (byte) 82;

            // Calculate and set checksum
            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

            DebugLogger.d("PacketBuilder", "  Checksum: 0x" + String.format("%02X", packet[28]));
            DebugLogger.logPacket("PacketBuilder", "Built ?6 packet", packet);

        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building identification packet", e);
        }

        return packet;
    }

    /**
     * Construct Heartbeat/Status Packet (?3)
     * Sent every 1 second to maintain connection
     */
    public static byte[] buildHeartbeatPacket(String time, boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?3 heartbeat packet");
        DebugLogger.d("PacketBuilder", "  Time: " + time);
        DebugLogger.d("PacketBuilder", "  Checksum: " + (usesInvertedChecksum ? "INVERTED" : "DIRECT"));

        byte[] packet = new byte[PACKET_SIZE];

        try {
            // Format: ?3 + 1 + Y + speed(3) + signal(1) + time(6) + padding
            // For testing, use fixed values: speed=000, signal=4
            String payload = "?31Y0004" + time + "0000000000000000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            System.arraycopy(payloadBytes, 0, packet, 0, Math.min(payloadBytes.length, PACKET_SIZE));

            // Set header
            packet[0] = HEADER;

            // Set padding to 0xFF
            for (int i = 14; i <= 27; i++) {
                packet[i] = (byte) 0xFF;
            }

            // Notification flags (N = No notification)
            packet[14] = (byte) 'N';
            packet[15] = (byte) 'N';

            // Calculate checksum and set footer
            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

            DebugLogger.d("PacketBuilder", "  Checksum: 0x" + String.format("%02X", packet[28]));
            DebugLogger.logPacket("PacketBuilder", "Built ?3 packet", packet);

        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building heartbeat packet", e);
        }

        return packet;
    }

    /**
     * Calculate checksum for bytes 1-27
     * Based on decompiled SuzukiApplication.a() logic:
     * - Device names starting with 'A' (Access, Burgman) use: 255 - (sum % 256)
     * [inverted]
     * - Device names starting with 'B' (Avenis, V-Strom, Gixxer) use: sum % 256
     * [direct]
     * 
     * @param packet              The packet data
     * @param useInvertedChecksum true for 'A' prefix models (inverted), false for
     *                            'B' prefix (direct)
     */
    public static byte calculateChecksum(byte[] packet, boolean useInvertedChecksum) {
        int sum = 0;
        for (int i = 1; i <= 27; i++) {
            sum += (packet[i] & 0xFF);
        }

        byte checksum;
        if (useInvertedChecksum) {
            // Inverted checksum for 'A' prefix models (Access 125, Burgman Street)
            checksum = (byte) (255 - (sum % 256));
            DebugLogger.d("Checksum", String.format("Inverted: sum=%d, 255-(sum%%256) = 0x%02X", sum, checksum & 0xFF));
        } else {
            // Direct checksum for 'B' prefix models (Avenis, V-Strom SX, Gixxer, SBM)
            checksum = (byte) (sum % 256);
            DebugLogger.d("Checksum", String.format("Direct: sum=%d, sum%%256 = 0x%02X", sum, checksum & 0xFF));
        }

        return checksum;
    }

    /**
     * Determine if device uses INVERTED checksum based on device name.
     * Device name format: S[A|B][S|M]XX where position 1 determines checksum type.
     * 
     * 'A' prefix (position 1) = Access, Burgman → uses INVERTED checksum (255 -
     * sum)
     * 'B' prefix (position 1) = Avenis, V-Strom, Gixxer, SBM → uses DIRECT checksum
     * (sum)
     * 
     * @return true if device uses inverted checksum ('A' prefix), false for direct
     *         ('B' prefix)
     */
    public static boolean usesInvertedChecksum(String deviceName) {
        if (deviceName == null || deviceName.length() < 2) {
            DebugLogger.w("ModelDetection",
                    "Device name too short: '" + deviceName + "', defaulting to direct checksum");
            return false;
        }

        // Check character at position 1 (0-indexed)
        char prefixChar = deviceName.charAt(1);
        boolean isInverted = (prefixChar == 'A');

        DebugLogger.i("ModelDetection", String.format(
                "Device '%s' prefix char[1]='%c' → %s checksum",
                deviceName, prefixChar, isInverted ? "INVERTED (255-sum)" : "DIRECT (sum)"));

        return isInverted;
    }

    /**
     * @deprecated Use usesInvertedChecksum() instead
     */
    @Deprecated
    public static boolean isDirectSumModel(String deviceName) {
        // For backward compatibility - inverts the result
        return !usesInvertedChecksum(deviceName);
    }
}
