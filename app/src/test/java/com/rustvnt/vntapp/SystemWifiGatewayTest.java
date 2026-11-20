package com.rustvnt.vntapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SystemWifiGatewayTest {
    private static final List<String> SPLIT = List.of("10.26.0.0/24", "192.168.2.0/24");

    @Test public void tableMirrorsVpnRoutesWithoutDefault() {
        String script = script(SPLIT, null);
        assertTrue(script.contains("ip route replace table 201 10.26.0.0/24 dev tun0"));
        assertTrue(script.contains("ip route replace table 201 192.168.2.0/24 dev tun0"));
        assertTrue(script.contains("ip route replace table 201 192.168.43.0/24 dev ap0"));
        assertTrue(script.contains("ip rule add iif ap0 lookup 201 pref 9999"));
        assertTrue(script.contains("iptables -I FORWARD 1 -i ap0 -o tun0 "));
        assertTrue(script.contains("iptables -I FORWARD 1 -i tun0 -o ap0 "));
        assertTrue(script.contains(
                "iptables -t nat -A POSTROUTING -s 192.168.43.0/24 -o tun0 -m comment --comment vntapp_wifi_gw -j MASQUERADE"));
        assertFalse(script.contains("default dev"));
        assertTrue(script.contains("echo 1 > /proc/sys/net/ipv4/ip_forward"));
        assertFalse(script.contains("--dport 53"));
    }

    @Test public void defaultRouteOnlyWhenInboundIsDefault() {
        String script = script(List.of("10.26.0.0/24", "0.0.0.0/0"), "8.8.8.8");
        assertTrue(script.contains("ip route replace table 201 default dev tun0"));
        assertFalse(script.contains("0.0.0.0/0 dev"));
        assertTrue(script.contains("--dport 53"));
        assertTrue(script.contains("--to-destination 8.8.8.8"));
        assertEquals("8.8.8.8", SystemWifiGateway.coveredDns(List.of("8.8.8.8", "1.1.1.1"),
                List.of("0.0.0.0/0")));
    }

    @Test public void dnsOutsideTunRoutesIsNotRewritten() {
        assertNull(SystemWifiGateway.coveredDns(List.of("8.8.8.8"), SPLIT));
        assertEquals("10.26.0.53", SystemWifiGateway.coveredDns(List.of("10.26.0.53", "8.8.8.8"), SPLIT));
        assertTrue(SystemWifiGateway.coveredBy("192.168.2.9", SPLIT));
        assertFalse(SystemWifiGateway.coveredBy("1.1.1.1", SPLIT));
    }

    @Test public void teardownRemovesPolicyRouteAndKeepsForwarding() {
        String script = SystemWifiGateway.teardownScript();
        assertTrue(script.contains("ip route flush table 201"));
        assertTrue(script.contains("del_pref 9999"));
        assertTrue(script.contains("cleanup_marked nat POSTROUTING"));
        assertTrue(script.contains("cleanup_marked nat PREROUTING"));
        assertTrue(script.contains("cleanup_marked filter FORWARD"));
        assertFalse(script.contains("ip_forward"));
        assertFalse(script.contains("default dev"));
    }

    @Test public void enableScriptRejectsUnsafeInterfaceName() {
        assertThrows(IllegalArgumentException.class, () -> SystemWifiGateway.enableScript(
                "tun0",
                List.of(new WifiSubnet.LocalSubnet("ap0;id", WifiSubnet.Kind.HOTSPOT,
                        new WifiSubnet.Cidr("192.168.43.0", 24))),
                SPLIT,
                null));
        assertThrows(IllegalArgumentException.class, () -> SystemWifiGateway.enableScript(
                "tun0;id",
                List.of(hotspot()),
                SPLIT,
                null));
    }

    private static String script(List<String> tunCidrs, String dns) {
        return SystemWifiGateway.enableScript("tun0", List.of(hotspot()), tunCidrs, dns);
    }

    private static WifiSubnet.LocalSubnet hotspot() {
        return new WifiSubnet.LocalSubnet("ap0", WifiSubnet.Kind.HOTSPOT,
                new WifiSubnet.Cidr("192.168.43.0", 24));
    }
}
