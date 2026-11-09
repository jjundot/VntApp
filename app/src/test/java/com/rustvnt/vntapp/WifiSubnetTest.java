package com.rustvnt.vntapp;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class WifiSubnetTest {
    @Test public void mergesWifiCidrAheadWhenMissing() {
        List<String> merged = WifiSubnet.mergeIntoOutput(
                List.of("10.0.0.0/8"), new WifiSubnet.Cidr("192.168.68.0", 24));
        assertEquals(List.of("192.168.68.0/24", "10.0.0.0/8"), merged);
    }

    @Test public void doesNotDuplicateEquivalentWifiCidr() {
        List<String> merged = WifiSubnet.mergeIntoOutput(
                List.of("192.168.68.10/24"), new WifiSubnet.Cidr("192.168.68.0", 24));
        assertEquals(List.of("192.168.68.10/24"), merged);
    }
}
