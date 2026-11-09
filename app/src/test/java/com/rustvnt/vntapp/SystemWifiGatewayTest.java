package com.rustvnt.vntapp;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SystemWifiGatewayTest {
    @Test public void enableScriptTargetsSelectedInterfaceAndCidr() {
        String script = SystemWifiGateway.enableScript(List.of(new WifiSubnet.LocalSubnet(
                "ap0", WifiSubnet.Kind.HOTSPOT, new WifiSubnet.Cidr("192.168.43.0", 24))));
        assertTrue(script.contains("iptables -t nat -A POSTROUTING -s 192.168.43.0/24 "));
        assertTrue(script.contains("iptables -I FORWARD 1 -i ap0 "));
        assertTrue(script.contains("iptables -I FORWARD 1 -o ap0 "));
        assertTrue(script.contains("vntapp_wifi_gw"));
    }

    @Test public void enableScriptRejectsUnsafeInterfaceName() {
        assertThrows(IllegalArgumentException.class, () -> SystemWifiGateway.enableScript(List.of(
                new WifiSubnet.LocalSubnet("ap0;id", WifiSubnet.Kind.HOTSPOT,
                        new WifiSubnet.Cidr("192.168.43.0", 24)))));
    }
}
