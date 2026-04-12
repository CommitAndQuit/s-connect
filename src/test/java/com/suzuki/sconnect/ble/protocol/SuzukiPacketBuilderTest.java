package com.suzuki.sconnect.ble.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuzukiPacketBuilderTest {

    @Test
    public void testBuildNavigationPacket() {
        int distance = 500;
        int iconId = 5;
        String eta = "1230PM";
        String status = "1";

        byte[] packet = SuzukiPacketBuilder.buildNavigationPacket(distance, iconId, eta, status, false);

        assertNotNull(packet);
        assertEquals(30, packet.length);
        assertEquals((byte) 0xA5, packet[0]);
        assertEquals((byte) iconId, packet[2]);
        assertEquals((byte) 0xFF, packet[3]);

        // Distance is 500m -> "0500"
        assertEquals('0', packet[4]);
        assertEquals('5', packet[5]);
        assertEquals('0', packet[6]);
        assertEquals('0', packet[7]);

        assertEquals('M', packet[8]);

        // ETA is "1230PM"
        assertEquals('1', packet[9]);
        assertEquals('2', packet[10]);
        assertEquals('3', packet[11]);
        assertEquals('0', packet[12]);
        assertEquals('P', packet[13]);
        assertEquals('M', packet[14]);

        assertEquals((byte) 0xFF, packet[15]);
        assertEquals((byte) 0xFF, packet[16]);
        assertEquals((byte) 0xFF, packet[17]);

        // Status 1
        assertEquals('1', packet[23]);

        assertEquals((byte) 0xFF, packet[25]);
        assertEquals((byte) 0xFF, packet[26]);
        assertEquals((byte) 0xFF, packet[27]);

        assertEquals((byte) 0x7F, packet[29]);
    }

    @Test
    public void testBuildNavigationPacket_overrideIcon() {
        // When status is not '1', '3', or '5', icon should be overridden to NONE
        byte[] packet = SuzukiPacketBuilder.buildNavigationPacket(500, 5, "1230PM", "2", false);
        assertEquals((byte) SuzukiPacketBuilder.TurnIcon.NONE, packet[2]);
    }

    @Test
    public void testMapMapplsToClusterIcon() {
        assertEquals(1, SuzukiPacketBuilder.mapMapplsToClusterIcon(0));
        assertEquals(9, SuzukiPacketBuilder.mapMapplsToClusterIcon(8));
        assertEquals(15, SuzukiPacketBuilder.mapMapplsToClusterIcon(53));
        assertEquals(SuzukiPacketBuilder.TurnIcon.NONE, SuzukiPacketBuilder.mapMapplsToClusterIcon(999));
    }
}
