package com.suzuki.sconnect.ble.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuzukiPacketParserTest {

    @Test
    public void testParseVehicleStatusPacket_valid() {
        byte[] packet = new byte[30];
        packet[0] = (byte) 0xA5;
        packet[1] = '7';

        // Speed: "042" (42 km/h)
        packet[2] = '0'; packet[3] = '4'; packet[4] = '2';
        // Odometer: "012345" (12345 km)
        packet[5] = '0'; packet[6] = '1'; packet[7] = '2'; packet[8] = '3'; packet[9] = '4'; packet[10] = '5';
        // Trip A: "001234" (123.4 km)
        packet[11] = '0'; packet[12] = '0'; packet[13] = '1'; packet[14] = '2'; packet[15] = '3'; packet[16] = '4';
        // Trip B: "000567" (56.7 km)
        packet[17] = '0'; packet[18] = '0'; packet[19] = '0'; packet[20] = '5'; packet[21] = '6'; packet[22] = '7';

        // Gear: 3 (binary)
        packet[23] = 3;
        // Fuel level: '4' (4 bars)
        packet[24] = '4';

        // Fuel Consumption bytes (25-27)
        // Let's create raw24 = (intPart << 11) | fracPart
        // intPart = 50 (for 5.0 before /10), fracPart = 1024 (for 0.5 before /10)
        // raw24 = (50 << 11) | 1024 = 102400 | 1024 = 103424 = 0x019400
        packet[25] = 0x01;
        packet[26] = (byte)0x94;
        packet[27] = 0x00;

        packet[28] = 0; // dummy checksum
        packet[29] = 0x7F; // footer

        SuzukiPacketParser.VehicleData data = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertTrue("Packet should be valid", data.isValid);
        assertEquals(42, data.speed);
        assertEquals(12345, data.odometer);
        assertEquals(123.4f, data.tripA, 0.01f);
        assertEquals(56.7f, data.tripB, 0.01f);
        assertEquals('3', data.gear);
        assertEquals(4, data.fuelLevel);

        // Fuel calculation: intPart=50, fracPart=1024
        // (50 + 1024/2048.0) / 10.0 = (50 + 0.5) / 10.0 = 5.05
        assertEquals(5.05f, data.fuelConsumption, 0.001f);
    }

    @Test
    public void testParseVehicleStatusPacket_invalidLength() {
        byte[] packet = new byte[29]; // Too short
        SuzukiPacketParser.VehicleData data = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertFalse(data.isValid);
    }

    @Test
    public void testParseVehicleStatusPacket_invalidHeader() {
        byte[] packet = new byte[30];
        packet[0] = 0x00; // Wrong header
        packet[29] = 0x7F;
        SuzukiPacketParser.VehicleData data = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertFalse(data.isValid);
    }

    @Test
    public void testParseVehicleStatusPacket_invalidFooter() {
        byte[] packet = new byte[30];
        packet[0] = (byte)0xA5;
        packet[29] = 0x00; // Wrong footer
        SuzukiPacketParser.VehicleData data = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertFalse(data.isValid);
    }

    @Test
    public void testParseVehicleStatusPacket_gearParsing() {
        byte[] packet = new byte[30];
        packet[0] = (byte) 0xA5;
        packet[1] = '7';
        packet[2] = '0'; packet[3] = '0'; packet[4] = '0';
        packet[5] = '0'; packet[6] = '0'; packet[7] = '0'; packet[8] = '0'; packet[9] = '0'; packet[10] = '0';
        packet[11] = '0'; packet[12] = '0'; packet[13] = '0'; packet[14] = '0'; packet[15] = '0'; packet[16] = '0';
        packet[17] = '0'; packet[18] = '0'; packet[19] = '0'; packet[20] = '0'; packet[21] = '0'; packet[22] = '0';
        packet[24] = '1';
        packet[25] = 0; packet[26] = 0; packet[27] = 0;
        packet[29] = 0x7F;

        // Test Neutral (binary)
        packet[23] = 0;
        SuzukiPacketParser.VehicleData data1 = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertEquals('N', data1.gear);

        // Test Gear 6 (binary)
        packet[23] = 6;
        SuzukiPacketParser.VehicleData data2 = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertEquals('6', data2.gear);

        // Test Neutral (ASCII)
        packet[23] = 'N';
        SuzukiPacketParser.VehicleData data3 = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertEquals('N', data3.gear);

        // Test Gear 4 (ASCII)
        packet[23] = '4';
        SuzukiPacketParser.VehicleData data4 = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertEquals('4', data4.gear);

        // Test unknown
        packet[23] = (byte) 0xFF;
        SuzukiPacketParser.VehicleData data5 = SuzukiPacketParser.parseVehicleStatusPacket(packet);
        assertEquals('-', data5.gear);
    }
}
