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
     * Sent every 1 second to maintain connection.
     *
     * Byte layout (from decompiled C0704v.java / ViewOnClickListenerC0708z):
     *   [0]    = 0xA5 (Header)
     *   [1]    = '3'
     *   [2-3]  = batteryStatus (e.g. "3N" = 75-100% not charging)
     *   [4-6]  = speed 3-digit ASCII (e.g. "060"). Set to 0xFF×3 if speed == 0.
     *   [7]    = signal 1-digit ASCII ("0"–"3"). Set to 0x00 if "0" (no signal).
     *   [8-13] = time in HHmmss ASCII. Set to 0xFF×6 if time == "000000".
     *   [14]   = call notification flag (HomeScreenActivity.e0)
     *   [15]   = SMS  notification flag (HomeScreenActivity.f0)
     *   [16-27]= 0xFF padding
     *   [28]   = checksum
     *   [29]   = 0x7F (Footer)
     *
     * @param batteryStatus        Battery status "[0-3][Y/N]"
     * @param speed                3-digit ASCII speed string, e.g. "060"
     * @param signal               1-digit signal level "0"–"3"
     * @param time                 Current time in HHmmss format
     * @param usesInvertedChecksum Checksum type
     */
    public static byte[] buildHeartbeatPacket(String batteryStatus, String speed, String signal,
            String time, boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?3 heartbeat packet");
        DebugLogger.d("PacketBuilder", "  Battery: " + batteryStatus);
        DebugLogger.d("PacketBuilder", "  Speed:   " + speed);
        DebugLogger.d("PacketBuilder", "  Signal:  " + signal);
        DebugLogger.d("PacketBuilder", "  Time:    " + time);
        DebugLogger.d("PacketBuilder", "  Checksum: " + (usesInvertedChecksum ? "INVERTED" : "DIRECT"));

        // Validate / sanitize inputs
        if (batteryStatus == null || batteryStatus.length() < 2) batteryStatus = "1N";
        if (speed == null || speed.length() != 3) speed = "000";
        if (signal == null || signal.isEmpty()) signal = "1";
        if (time == null || time.length() != 6) time = "000000";

        byte[] packet = new byte[PACKET_SIZE];

        try {
            // Build the 30-byte string skeleton:
            // "?3" + battery(2) + speed(3) + signal(1) + time(6) + 16 zeros = 30 chars
            String payload = "?3" + batteryStatus + speed + signal + time + "0000000000000000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(payloadBytes, 0, packet, 0, Math.min(payloadBytes.length, PACKET_SIZE));

            // [0] Header
            packet[0] = HEADER;

            // [4-6] Speed bytes: set to 0xFF when speed is 0 (matches decompiled logic)
            int speedVal = 0;
            try { speedVal = Integer.parseInt(speed); } catch (NumberFormatException ignored) {}
            if (speedVal == 0) {
                packet[4] = (byte) 0xFF;
                packet[5] = (byte) 0xFF;
                packet[6] = (byte) 0xFF;
            }

            // [7] Signal byte: set to 0x00 when no signal (matches decompiled: if H=="0" iArr[7]=0)
            if ("0".equals(signal)) {
                packet[7] = (byte) 0x00;
            }

            // [8-13] Time bytes: 0xFF when time is unset
            if ("000000".equals(time)) {
                for (int i = 8; i <= 13; i++) {
                    packet[i] = (byte) 0xFF;
                }
            }

            // [14-15] Notification flags (N = No notification pending)
            packet[14] = (byte) 'N';
            packet[15] = (byte) 'N';

            // [16-27] Padding
            for (int i = 16; i <= 27; i++) {
                packet[i] = (byte) 0xFF;
            }

            // [28-29] Checksum + Footer
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
     * Mapped to Suzuki Instrument Cluster (Mappls ID -> Suzuki ID)
     */
    public static class TurnIcon {
        public static final int NONE = 46;
        public static final int STRAIGHT = 1;
        public static final int TURN_LEFT_2 = 2; // Pattern mapping from w0.java
        public static final int SLIGHT_LEFT = 3;
        public static final int TURN_LEFT = 4;
        public static final int SLIGHT_RIGHT = 5;
        public static final int TURN_RIGHT = 6;
        public static final int U_TURN_LEFT = 7;
        public static final int U_TURN_RIGHT = 8;
        public static final int ROUNDABOUT = 9;
        public static final int ROUNDABOUT_EXIT_1 = 9;
        public static final int ROUNDABOUT_EXIT_2 = 9;
        public static final int FAST_CONNECTION = 10;
        public static final int ICON_11 = 11;
        public static final int ICON_12 = 12;
        public static final int ICON_13 = 13;
        public static final int ICON_14 = 14;
        public static final int DESTINATION = 15;
        public static final int ARRIVE = 15;
        public static final int ICON_16 = 16;
        public static final int ICON_17 = 17;
        public static final int ICON_18 = 18;
        public static final int ICON_19 = 19;
        public static final int ICON_20 = 20;
        public static final int ICON_21 = 21;
        public static final int ICON_22 = 22;
        public static final int ICON_23 = 23;
        public static final int ICON_24 = 24;
        public static final int ICON_25 = 25;
        public static final int ICON_26 = 26;
        public static final int ICON_27 = 27;
        public static final int ICON_28 = 28;
        public static final int ICON_29 = 29;
        public static final int ICON_30 = 30;
        public static final int FERRY = 31;
        public static final int ICON_32 = 32;
        public static final int ICON_33 = 33;
        public static final int ICON_34 = 34;
        public static final int ICON_35 = 35;
        public static final int ICON_36 = 36;
        public static final int ICON_37 = 37;
        public static final int WEATHER_RAIN = 38;
        public static final int WEATHER_TRAFFIC = 39;
        public static final int ICON_40 = 40;
        public static final int ICON_41 = 41;
        public static final int ICON_42 = 42;
        public static final int ICON_43 = 43;
        public static final int ICON_44 = 44;
        public static final int WEATHER_ALERT = 45;
    }

    /**
     * Maps Mappls Maneuver ID to Suzuki Instrument Cluster Icon ID.
     * Table extracted from decompiled original Suzuki app (w0.java).
     *
     * @param mapplsId The maneuver lookup ID from Mappls SDK
     * @return The remapped Suzuki cluster icon ID (byte value)
     */
    public static int mapMapplsToClusterIcon(int mapplsId) {
        switch (mapplsId) {
            case 0:
                return 1; // Straight
            case 1:
                return 2;
            case 2:
                return 3;
            case 3:
                return 4;
            case 4:
                return 5;
            case 5:
                return 6;
            case 6:
                return 7;
            case 7:
                return 8;
            case 8:
            case 9:
            case 10:
                return 9; // Roundabouts
            case 75:
                return 10;
            case 11:
                return 11;
            case 12:
                return 12;
            case 13:
                return 13;
            case 14:
                return 14;
            case 53:
                return 15; // Destination/Arrive
            case 54:
                return 16;
            case 55:
                return 17;
            case 56:
                return 18;
            case 57:
                return 19;
            case 65:
                return 20;
            case 66:
                return 21;
            case 67:
                return 22;
            case 68:
                return 23;
            case 69:
                return 24;
            case 70:
                return 25;
            case 71:
                return 26;
            case 19:
                return 27;
            case 20:
                return 28;
            case 17:
                return 29;
            case 18:
                return 30;
            case 15:
                return 31;
            case 16:
            case 31:
                return 32;
            case 28:
                return 31;
            case 21:
                return 33;
            case 22:
                return 34;
            case 23:
                return 35;
            case 24:
                return 36;
            case 25:
                return 37;
            case 73:
                return 38;
            case 41:
                return 39;
            case 50:
                return 40;
            case 51:
                return 41;
            case 52:
                return 42;
            case 36:
                return 43;
            case 74:
                return 44;
            case 72:
                return 45;
            default:
                return TurnIcon.NONE;
        }
    }

    /**
     * Construct Navigation Packet (?1)
     * Sent to update turn-by-turn guidance on the cluster
     *
     * REVERSE-ENGINEERED STRUCTURE from decompiled Suzuki app (w0.java):
     * [0] = 0xA5 (Header)
     * [1] = '1' (Navigation packet type)
     * [2] = Maneuver Icon ID (byte)
     * [3] = 0xFF (padding)
     * [4-7] = Distance 1 in meters/km (4 ASCII digits, e.g., "0250")
     * [8] = Unit 1 ('M' for meters, 'K' for km)
     * [9-14] = ETA Time (6 ASCII digits, e.g., "0530PM")
     * [15-17] = 0xFF, 0xFF, 0xFF (padding)
     * [18-21] = Distance 2 (usually same as Distance 1)
     * [22] = Unit 2
     * [23] = Status 1 ('1' = normal, '2' = rerouting, '4' = GPS lost, '5' =
     * arrived)
     * [24] = Status 2 ('1' = active, '0' = exit)
     * [25-27] = 0xFF, 0xFF, 0xFF (padding)
     * [28] = Checksum
     * [29] = Footer (0x7F)
     *
     * @param distanceMeters       Distance to next maneuver
     * @param turnIconId           ID of the turn icon (Binary byte value)
     * @param etaStr               ETA time string (6 chars, e.g. "0530PM")
     * @param statusCode           Status code ("1"=normal, "2"=reroute, "4"=gps
     *                             lost, "5"=arrived)
     * @param usesInvertedChecksum Checksum type
     */
    public static byte[] buildNavigationPacket(int distanceMeters, int totalDistanceRemaining, int turnIconId, String etaStr, String statusCode,
            boolean usesInvertedChecksum) {
        DebugLogger.d("PacketBuilder", "Building ?1 packet: Dist=" + distanceMeters + "m, TotalDist=" + totalDistanceRemaining + "m, Icon=" + turnIconId + ", ETA="
                + etaStr + ", Status=" + statusCode);

        byte[] packet = new byte[PACKET_SIZE];
        try {
            // 1. Prepare distance component
            String distStr;
            String unit;

            if (distanceMeters < 1000) {
                distStr = String.format("%04d", distanceMeters);
                unit = "M";
            } else {
                // For km, show like "1.2" as "01.2" but protocol expects 4 digits
                // Looking at w0.java, it uses float formatting.
                // Simplified: round to km for now if > 1km to avoid complex decimal logic in
                // test
                double km = distanceMeters / 1000.0;
                if (km < 10) {
                    distStr = String.format("%04.1f", km).replace(".", ""); // "1.2" -> "012" -> pad to 4?
                    // No, w0.java does: substring(1, 4) etc.
                    // Actually, let's keep it simple as "0001" K for 1km for now.
                    distStr = String.format("%04d", Math.round(km));
                } else {
                    distStr = String.format("%04d", Math.round(km));
                }
                unit = "K";
            }

            // Prepare total distance component
            String totalDistStr;
            String totalUnit;

            if (totalDistanceRemaining < 1000) {
                totalDistStr = String.format("%04d", totalDistanceRemaining);
                totalUnit = "M";
            } else {
                double totalKm = totalDistanceRemaining / 1000.0;
                if (totalKm < 10) {
                    totalDistStr = String.format("%04.1f", totalKm).replace(".", "");
                    totalDistStr = String.format("%04d", Math.round(totalKm)); // fallback to same logic
                } else {
                    totalDistStr = String.format("%04d", Math.round(totalKm));
                }
                totalUnit = "K";
            }

            // ETA must be 6 chars. Pad if necessary.
            if (etaStr == null || etaStr.length() != 6) {
                etaStr = "1200PM";
            }

            String status1 = statusCode != null ? statusCode : "1";
            String status2 = "1"; // Active

            // 2. Build base string (30 chars)
            // Indices: 0123 4567 8 901234 567 8901 2 3 4 567 8 9
            // Content: ?110 dist U ETA--- 000 tdst U S S 000 C F
            String payload = "?110" + distStr + unit + etaStr + "000" + totalDistStr + totalUnit + status1 + status2 + "00000";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            // 3. Copy to packet array
            System.arraycopy(payloadBytes, 0, packet, 0, Math.min(payloadBytes.length, PACKET_SIZE));

            // 4. Manual overrides for binary/protocol logic
            packet[0] = HEADER; // 0xA5

            // Override icon to NONE (46) if status is not normal navigating (1, 3, or 5)
            // This prevents old turn arrows from persisting during reroute/gps lost states
            int effectiveIconId = turnIconId;
            if (!"1".equals(statusCode) && !"3".equals(statusCode) && !"5".equals(statusCode)) {
                effectiveIconId = TurnIcon.NONE;
            }

            packet[2] = (byte) effectiveIconId; // Real binary Maneuver Icon ID
            packet[3] = (byte) 0xFF;

            // Padding bytes 15-17
            packet[15] = (byte) 0xFF;
            packet[16] = (byte) 0xFF;
            packet[17] = (byte) 0xFF;

            // Padding bytes 25-27
            for (int i = 25; i <= 27; i++) {
                packet[i] = (byte) 0xFF;
            }

            // 5. Checksum & Footer
            packet[28] = calculateChecksum(packet, usesInvertedChecksum);
            packet[29] = FOOTER;

        } catch (Exception e) {
            DebugLogger.e("PacketBuilder", "Error building navigation packet", e);
        }
        return packet;
    }

}
