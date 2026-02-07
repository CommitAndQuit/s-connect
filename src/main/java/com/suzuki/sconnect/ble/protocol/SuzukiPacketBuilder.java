package com.suzuki.sconnect.ble.protocol;

import com.suzuki.sconnect.utils.DebugLogger;

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
     * 
     * @param batteryStatus        Battery status encoded as [0-3][Y/N]
     * @param time                 Current time in HHmmss format
     * @param usesInvertedChecksum Checksum type
     */
    public static byte[] buildHeartbeatPacket(String batteryStatus, String time, boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?3 heartbeat packet");
        DebugLogger.d("PacketBuilder", "  Battery: " + batteryStatus);
        DebugLogger.d("PacketBuilder", "  Time: " + time);
        DebugLogger.d("PacketBuilder", "  Checksum: " + (usesInvertedChecksum ? "INVERTED" : "DIRECT"));

        byte[] packet = new byte[PACKET_SIZE];

        try {
            // Format: ?3 + batteryStatus(2) + speed(3) + signal(1) + time(6) + padding
            // For testing, use fixed values: speed=000, signal=4
            String payload = "?3" + batteryStatus + "0004" + time + "0000000000000000";
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

    /**
     * Construct Call Packet (?2)
     * Sent when there is an incoming call or ongoing call updates
     * 
     * Exact byte layout from decompiled CallReceiverBroadcast.d():
     * [0] = 0xA5 (Header)
     * [1] = '2' (0x32, from "?2" string - ? becomes header)
     * [2-21] = name (20 chars, null-padded)
     * [22] = 'N' (0x4E) for normal call, 'W' for WhatsApp call
     * [23] = '1' (0x31) for incoming, '2' (0x32) for offhook
     * [24-27] = 0xFF padding
     * [28] = checksum
     * [29] = 0x7F (Footer)
     *
     * @param name                 Caller name or number
     * @param status               1 = Incoming, 2 = Offhook/Ongoing
     * @param usesInvertedChecksum Checksum type
     */
    public static byte[] buildCallPacket(String name, int status, boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?2 call packet");
        DebugLogger.d("PacketBuilder", "  Name: " + name + ", Status: " + status);

        byte[] packet = new byte[PACKET_SIZE];
        try {
            // Sanitize name
            if (name == null || name.isEmpty()) {
                name = "Unknown";
            }
            name = name.replace("+", "");

            // Pad name to exactly 20 characters (decompiled: length <= 20, pad with \u0000)
            StringBuilder sb = new StringBuilder(name);
            while (sb.length() < 20) {
                sb.append('\u0000');
            }
            String paddedName = sb.length() > 20 ? sb.substring(0, 20) : sb.toString();

            // Build payload: "?2" + name(20) + "N1" + padding
            // Status: 1 = incoming (char '1'), 2 = offhook (char '2')
            char statusChar = (status == 2) ? '2' : '1';
            String payload = "?2" + paddedName + "N" + statusChar + "\u0000\u0000\u0000\u0000\u0000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            // Copy to packet
            System.arraycopy(payloadBytes, 0, packet, 0, Math.min(payloadBytes.length, PACKET_SIZE));

            // Overwrite control bytes
            packet[0] = HEADER; // 0xA5
            packet[22] = (byte) 'N'; // 0x4E - Call type indicator
            packet[23] = (byte) statusChar; // '1' or '2'

            // Padding bytes 24-27
            for (int i = 24; i <= 27; i++) {
                packet[i] = (byte) 0xFF;
            }

            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

            DebugLogger.logPacket("PacketBuilder", "Built ?2 packet", packet);
        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building call packet", e);
        }
        return packet;
    }

    /**
     * Construct Missed Call Packet (?4)
     * 
     * Exact byte layout from decompiled CallReceiverBroadcast.e():
     * [0] = 0xA5 (Header)
     * [1] = '4' (from "?4")
     * [2] = 0xFF
     * [3] = count (missed call count / sync ID)
     * [4-25] = name (22 chars, null-padded) - note: some overlap with control
     * [24] = 'N' (0x4E)
     * [25-27] = 0xFF
     * [28] = checksum
     * [29] = 0x7F (Footer)
     *
     * @param name                 Caller name
     * @param count                Missed call count
     * @param usesInvertedChecksum Checksum type
     */
    public static byte[] buildMissedCallPacket(String name, int count, boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?4 missed call packet");
        DebugLogger.d("PacketBuilder", "  Name: " + name + ", Count: " + count);

        byte[] packet = new byte[PACKET_SIZE];
        try {
            // Sanitize
            if (name == null || name.isEmpty()) {
                name = "Unknown";
            }
            name = name.replace("+", "");

            // Pad name to 22 chars
            StringBuilder sb = new StringBuilder(name);
            while (sb.length() < 22) {
                sb.append('\u0000');
            }
            String paddedName = sb.length() > 22 ? sb.substring(0, 22) : sb.toString();

            // Build payload: "?4" + name(22) + padding
            String payload = "?4" + paddedName + "\u0000\u0000\u0000\u0000\u0000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(payloadBytes, 0, packet, 0, Math.min(payloadBytes.length, PACKET_SIZE));

            // Overwrite control bytes
            packet[0] = HEADER;
            packet[2] = (byte) 0xFF;
            packet[3] = (byte) count; // Missed call count

            packet[24] = (byte) 'N'; // Type indicator

            for (int i = 25; i <= 27; i++) {
                packet[i] = (byte) 0xFF;
            }

            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

            DebugLogger.logPacket("PacketBuilder", "Built ?4 packet", packet);
        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building missed call packet", e);
        }
        return packet;
    }

    /**
     * Construct Notification Packet (?5)
     * For SMS, WhatsApp, etc.
     * 
     * Exact byte layout from decompiled NotificationService.i():
     * [0] = 0xA5 (Header)
     * [1] = '5' (0x35, from "?5" string - but ? becomes header)
     * [2] = 0xFF
     * [3] = 0x59 ('Y')
     * [4] = count (notification count)
     * [5-24] = title/sender (20 chars, from original string position)
     * [25] = message type byte
     * [26] = 0xFF
     * [27] = 0xFF
     * [28] = checksum
     * [29] = 0x7F (Footer)
     *
     * @param title Title/Sender (max ~20 chars effectively)
     * @param count Notification count (for badge)
     * @param type  1=SMS, 2=WhatsApp, 3=Calendar
     */
    public static byte[] buildNotificationPacket(String title, int count, int type, boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?5 notification packet");
        DebugLogger.d("PacketBuilder", "  Title: " + title + ", Count: " + count + ", Type: " + type);

        byte[] packet = new byte[PACKET_SIZE];
        try {
            // Sanitize title
            if (title == null) {
                title = "Message";
            }
            title = title.replace("+", "");

            // Determine type character
            String typeChar;
            switch (type) {
                case 2:
                    typeChar = "W";
                    break; // WhatsApp
                case 3:
                    typeChar = "X";
                    break; // Calendar
                default:
                    typeChar = "N";
                    break; // SMS/Default
            }

            // Decompiled logic: pad title to 24 chars (length <= 23 means pad until length
            // = 24)
            // Then append typeChar + padding
            StringBuilder sb = new StringBuilder(title);
            while (sb.length() < 24) {
                sb.append('\u0000');
            }
            String paddedTitle = sb.length() > 24 ? sb.substring(0, 24) : sb.toString();

            // Build initial string: "?5" + paddedTitle(24) + typeChar + padding
            // Total: 2 + 24 + 1 + 3 = 30 bytes
            String payload = "?5" + paddedTitle + typeChar + "\u0000\u0000\u0000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            // Copy to packet (this puts title starting at byte 2)
            System.arraycopy(payloadBytes, 0, packet, 0, Math.min(payloadBytes.length, PACKET_SIZE));

            // Now overwrite specific control bytes (this is what decompiled does!)
            packet[0] = HEADER; // 0xA5, overwrites '?'
            packet[2] = (byte) 0xFF; // Overwrites title[0]
            packet[3] = (byte) 89; // 'Y', overwrites title[1]
            packet[4] = (byte) count; // Count, overwrites title[2]

            // The actual displayable title starts at byte 5 and goes to ~24
            // So effectively title[3:] is what gets displayed (first 3 chars are lost to
            // control bytes)

            packet[25] = (byte) type; // Message type (1=SMS, 2=WA, etc)
            packet[26] = (byte) 0xFF;
            packet[27] = (byte) 0xFF;
            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

            DebugLogger.logPacket("PacketBuilder", "Built ?5 packet", packet);

        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building notification packet", e);
        }
        return packet;
    }

    /**
     * Navigation Turn Icon Constants
     * Mapped to Suzuki Instrument Cluster byte codes (Hypothetical mapping)
     */
    public static class TurnIcon {
        public static final int NONE = 0;
        public static final int STRAIGHT = 1;
        public static final int TURN_LEFT = 2;
        public static final int TURN_RIGHT = 3;
        public static final int SLIGHT_LEFT = 4;
        public static final int SLIGHT_RIGHT = 5;
        public static final int U_TURN_LEFT = 6;
        public static final int U_TURN_RIGHT = 7;
        public static final int ROUNDABOUT = 8;
        public static final int DESTINATION = 9;
    }

    /**
     * Construct Navigation Packet (?1)
     * Sent to update turn-by-turn guidance on the cluster
     *
     * REVERSE-ENGINEERED STRUCTURE from decompiled Suzuki app (w0.java:377):
     * [0] = 0xA5 (Header)
     * [1] = '1' (Navigation packet type - NOT '8'!)
     * [2] = Maneuver Icon ID (byte)
     * [3] = 0xFF (padding)
     * [4-7] = Distance in meters (4 ASCII digits, e.g., "0250")
     * [8-11] = Turn direction code (4 ASCII digits)
     * [12-14] = "000" (padding)
     * [15-18] = Secondary info (4 ASCII digits)
     * [19-21] = Road info (3 ASCII chars)
     * [22] = Network status ('1' = normal, '4' = GPS lost, '0' = no network)
     * [23] = Exit code ('1' = active, '0' = exit navigation)
     * [24] = '0'
     * [25-27] = 0xFF (padding)
     * [28] = Checksum
     * [29] = Footer
     *
     * @param distanceMeters Distance to next maneuver
     * @param turnIconId     ID of the turn icon (from TurnIcon class)
     * @param instruction    Short text instruction (NOT USED in actual protocol)
     */
    public static byte[] buildNavigationPacket(int distanceMeters, int turnIconId, String instruction,
            boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?1 navigation packet (REVERSE-ENGINEERED)");
        DebugLogger.d("PacketBuilder",
                "  Dist: " + distanceMeters + "m, Icon: " + turnIconId);

        byte[] packet = new byte[PACKET_SIZE];
        try {
            // Build string template (24 bytes total after '?' replacement)
            // Example: "?110025004500000000000110" (24 chars)

            // Format distance as 4 digits
            String distStr = String.format("%04d", Math.min(distanceMeters, 9999));

            // Turn direction code (simplified - using icon ID as placeholder)
            String turnCode = String.format("%02d", turnIconId % 100);

            // Build packet string base (approx 24 bytes)
            String packetStr = "?110" + distStr + turnCode + "00" + "000" + "0000" + "000" + "1" + "1" + "0";
            byte[] strBytes = packetStr.getBytes(StandardCharsets.UTF_8);

            // Copy up to index 24 (leave room for padding, checksum, footer)
            System.arraycopy(strBytes, 0, packet, 0, Math.min(strBytes.length, 25));

            // Override specific bytes
            packet[0] = HEADER; // 0xA5
            packet[2] = (byte) turnIconId; // Maneuver icon ID
            packet[3] = (byte) 0xFF; // Padding

            // Clear road info / padding bytes 25-27
            for (int i = 25; i <= 27; i++) {
                packet[i] = (byte) 0xFF;
            }

            // Checksum & Footer
            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

            DebugLogger.logPacket("PacketBuilder", "Built ?1 packet", packet);

        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building navigation packet", e);
        }
        return packet;
    }

}
