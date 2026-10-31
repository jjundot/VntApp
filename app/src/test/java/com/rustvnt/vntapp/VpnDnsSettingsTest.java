package com.rustvnt.vntapp;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class VpnDnsSettingsTest {
    @Test public void defaultsAreEnabledAndUseRequestedPublicServers() {
        assertTrue(VpnDnsSettings.DEFAULT.enabled);
        assertEquals(List.of("8.8.8.8", "1.1.1.1"), VpnDnsSettings.DEFAULT.servers());
        assertEquals(VpnDnsSettings.DEFAULT.servers(), new VpnDnsSettings(true, " \n").servers());
    }

    @Test public void customServersReplaceDefaultsAndDeduplicate() {
        assertEquals(List.of("10.26.0.3", "192.168.1.53"),
                new VpnDnsSettings(true, "10.26.0.3, 192.168.1.53\n10.26.0.3，10.26.0.3").servers());
    }

    @Test public void disabledDoesNotConfigureDnsOrRoutesEvenWithInvalidSavedInput() {
        RecordingSink sink = new RecordingSink();
        new VpnDnsSettings(false, "old.invalid").apply(sink);
        assertTrue(sink.calls.isEmpty());
    }

    @Test public void everyNewInterfaceGetsBothDnsAndHostRoutes() {
        VpnDnsSettings settings = new VpnDnsSettings(true, "10.26.0.3,8.8.8.8");
        RecordingSink initial = new RecordingSink();
        RecordingSink replacement = new RecordingSink();
        settings.apply(initial);
        settings.apply(replacement);
        assertEquals(List.of("dns:10.26.0.3", "route:10.26.0.3/32",
                "dns:8.8.8.8", "route:8.8.8.8/32"), initial.calls);
        assertEquals(initial.calls, replacement.calls);
    }

    @Test public void rejectsHostnamesPortsIpv6AndUnusableAddresses() {
        for (String invalid : List.of("dns.google", "8.8.8.8:53", "2001:4860:4860::8888",
                "256.1.1.1", "127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
                "+8.8.8.8", "08.8.8.8", "8.8.8", "8.8.8.8/32", ",，")) {
            assertThrows(invalid, IllegalArgumentException.class,
                    () -> new VpnDnsSettings(true, invalid).servers());
        }
    }

    private static final class RecordingSink implements VpnDnsSettings.Sink {
        final List<String> calls = new ArrayList<>();
        @Override public void addDnsServer(String server) { calls.add("dns:" + server); }
        @Override public void addRoute(String address, int prefix) {
            calls.add("route:" + address + "/" + prefix);
        }
    }
}
