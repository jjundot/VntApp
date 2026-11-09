package com.rustvnt.vntapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONObject;
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

    @Test public void networkCidrMasksHostBits() {
        assertEquals("192.168.43.0/24", WifiSubnet.networkCidr("192.168.43.1", 24).value());
        assertEquals("10.8.0.0/16", WifiSubnet.networkCidr("10.8.1.1", 16).value());
        assertEquals("172.16.0.0/12", WifiSubnet.networkCidr("172.16.5.9", 12).value());
        assertEquals("10.0.0.0/8", WifiSubnet.networkCidr("10.1.2.3", 8).value());
        assertEquals("192.168.43.1/32", WifiSubnet.networkCidr("192.168.43.1", 32).value());
        assertNull(WifiSubnet.networkCidr("192.168.1.1", -1));
        assertNull(WifiSubnet.networkCidr("192.168.1", 24));
        assertNull(WifiSubnet.networkCidr("192.168.1.256", 24));
    }

    @Test public void classifiesInterfaceNames() {
        assertEquals(WifiSubnet.Kind.HOTSPOT, WifiSubnet.classify("ap0", null));
        assertEquals(WifiSubnet.Kind.HOTSPOT, WifiSubnet.classify("ap_br_wlan0", null));
        assertEquals(WifiSubnet.Kind.HOTSPOT, WifiSubnet.classify("swlan0", null));
        assertEquals(WifiSubnet.Kind.HOTSPOT, WifiSubnet.classify("wlan1", null));
        assertEquals(WifiSubnet.Kind.HOTSPOT, WifiSubnet.classify("softap0", null));
        assertEquals(WifiSubnet.Kind.HOTSPOT, WifiSubnet.classify("wlan0", WifiSubnet.Kind.HOTSPOT));
        assertEquals(WifiSubnet.Kind.WIFI, WifiSubnet.classify("wlan0", null));
        assertEquals(WifiSubnet.Kind.WIFI, WifiSubnet.classify("wifi0", null));
        assertEquals(WifiSubnet.Kind.WIFI, WifiSubnet.classify("oem0", WifiSubnet.Kind.WIFI));
        assertEquals(WifiSubnet.Kind.USB, WifiSubnet.classify("rndis0", null));
        assertEquals(WifiSubnet.Kind.USB, WifiSubnet.classify("usb0", null));
        assertEquals(WifiSubnet.Kind.USB, WifiSubnet.classify("ncm0", null));
        assertEquals(WifiSubnet.Kind.ETHERNET, WifiSubnet.classify("eth0", null));
        assertEquals(WifiSubnet.Kind.CELLULAR, WifiSubnet.classify("rmnet_data0", null));
        assertEquals(WifiSubnet.Kind.CELLULAR, WifiSubnet.classify("ccmni0", null));
        assertEquals(WifiSubnet.Kind.CELLULAR, WifiSubnet.classify("wwan0", null));
        assertEquals(WifiSubnet.Kind.OTHER, WifiSubnet.classify("dummy0", null));
        assertEquals(WifiSubnet.Kind.OTHER, WifiSubnet.classify("tun0", null));
        assertTrue(WifiSubnet.isTun("tun0"));
        assertTrue(WifiSubnet.isTun("TUN1"));
    }

    @Test public void kindMatchUsesCurrentCidrWhenHotspotSubnetChanges() {
        WifiSubnet.Selection saved = new WifiSubnet.Selection(
                "ap0", WifiSubnet.Kind.HOTSPOT, "192.168.43.0/24");
        WifiSubnet.LocalSubnet now = new WifiSubnet.LocalSubnet(
                "ap0", WifiSubnet.Kind.HOTSPOT, WifiSubnet.networkCidr("192.168.49.8", 24));
        WifiSubnet.Resolution resolution = WifiSubnet.resolve(List.of(saved), List.of(now), true);
        assertEquals("192.168.49.0/24", resolution.matched().get(0).cidr().value());
        assertTrue(resolution.skipped().isEmpty());
    }

    @Test public void kindMatchFindsRenamedHotspot() {
        WifiSubnet.Selection saved = new WifiSubnet.Selection(
                "ap0", WifiSubnet.Kind.HOTSPOT, "192.168.43.0/24");
        WifiSubnet.LocalSubnet renamed = new WifiSubnet.LocalSubnet(
                "swlan0", WifiSubnet.Kind.HOTSPOT, new WifiSubnet.Cidr("192.168.49.0", 24));
        WifiSubnet.Resolution resolution = WifiSubnet.resolve(List.of(saved), List.of(renamed), true);
        assertEquals("swlan0", resolution.matched().get(0).name());
        assertEquals("192.168.49.0/24", resolution.matched().get(0).cidr().value());
    }

    @Test public void exactCidrMatchesDifferentInterface() {
        WifiSubnet.Selection saved = new WifiSubnet.Selection(
                "eth0", WifiSubnet.Kind.ETHERNET, "192.168.5.9/24");
        WifiSubnet.LocalSubnet usb = new WifiSubnet.LocalSubnet(
                "usb0", WifiSubnet.Kind.USB, WifiSubnet.networkCidr("192.168.5.20", 24));
        WifiSubnet.Resolution resolution = WifiSubnet.resolve(List.of(saved), List.of(usb), true);
        assertEquals("usb0", resolution.matched().get(0).name());
        assertEquals("192.168.5.0/24", resolution.matched().get(0).cidr().value());
    }

    @Test public void absentSelectionIsSkipped() {
        WifiSubnet.Selection saved = new WifiSubnet.Selection(
                "wlan0", WifiSubnet.Kind.WIFI, "10.9.0.0/24");
        WifiSubnet.LocalSubnet cellular = new WifiSubnet.LocalSubnet(
                "rmnet_data0", WifiSubnet.Kind.CELLULAR, WifiSubnet.networkCidr("10.20.1.5", 8));
        WifiSubnet.Resolution resolution = WifiSubnet.resolve(List.of(saved), List.of(cellular), true);
        assertTrue(resolution.matched().isEmpty());
        assertEquals(1, resolution.skipped().size());
    }

    @Test public void legacySelectionPrefersHotspotThenWifiAndExplicitEmptyDoesNotFallBack() {
        WifiSubnet.LocalSubnet wifi = new WifiSubnet.LocalSubnet(
                "wlan0", WifiSubnet.Kind.WIFI, new WifiSubnet.Cidr("192.168.1.0", 24));
        WifiSubnet.LocalSubnet hotspot = new WifiSubnet.LocalSubnet(
                "ap0", WifiSubnet.Kind.HOTSPOT, new WifiSubnet.Cidr("192.168.43.0", 24));
        assertEquals("ap0", WifiSubnet.resolve(null, List.of(wifi, hotspot), true).matched().get(0).name());
        assertEquals("wlan0", WifiSubnet.resolve(null, List.of(wifi), true).matched().get(0).name());
        assertTrue(WifiSubnet.resolve(List.of(), List.of(hotspot, wifi), true).matched().isEmpty());
        assertTrue(WifiSubnet.resolve(null, List.of(hotspot), false).matched().isEmpty());
    }

    @Test public void selectionsRoundTripAndMissingKeyStaysLegacy() throws Exception {
        JSONObject config = new JSONObject();
        assertNull(WifiSubnet.readSelections(config));
        WifiSubnet.writeSelections(config, List.of(new WifiSubnet.Selection(
                "ap0", WifiSubnet.Kind.HOTSPOT, "192.168.43.0/24")));
        List<WifiSubnet.Selection> read = WifiSubnet.readSelections(config);
        assertEquals("ap0", read.get(0).name());
        assertEquals(WifiSubnet.Kind.HOTSPOT, read.get(0).kind());
        assertEquals("192.168.43.0/24", read.get(0).cidr());
        WifiSubnet.writeSelections(config, List.of());
        assertTrue(WifiSubnet.readSelections(config).isEmpty());
    }
}
