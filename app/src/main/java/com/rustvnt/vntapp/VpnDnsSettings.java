package com.rustvnt.vntapp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Android-only settings; never sent to or replaced by the managed core configuration. */
final class VpnDnsSettings {
    static final VpnDnsSettings DEFAULT = new VpnDnsSettings(true, "");
    private static final List<String> DEFAULT_SERVERS = List.of("8.8.8.8", "1.1.1.1");
    final boolean enabled;
    final String customServers;

    VpnDnsSettings(boolean enabled, String customServers) {
        this.enabled = enabled;
        this.customServers = customServers == null ? "" : customServers.trim();
    }

    interface Sink {
        void addDnsServer(String server);
        void addRoute(String address, int prefix);
    }

    void apply(Sink sink) {
        for (String server : servers()) {
            sink.addDnsServer(server);
            sink.addRoute(server, 32);
        }
    }

    List<String> servers() {
        if (!enabled) return List.of();
        if (customServers.isEmpty()) return DEFAULT_SERVERS;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String entry : customServers.split("[\\s,，]+")) {
            if (entry.isEmpty()) continue;
            result.add(validateServer(entry));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("请填写 DNS 服务器 IPv4 地址");
        return new ArrayList<>(result);
    }

    /** Numeric IPv4 only: avoid resolving user input outside the tunnel. */
    private static String validateServer(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw invalid(value);
        int[] address = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw invalid(value);
            address[i] = Integer.parseInt(octets[i]);
            if (address[i] > 255) throw invalid(value);
        }
        // VNT's TUN data plane supports IPv4 only. Loopback, unspecified,
        // multicast and reserved/broadcast addresses cannot be DNS upstreams.
        if (address[0] == 0 || address[0] == 127 || address[0] >= 224) throw invalid(value);
        return value;
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("无效 DNS 服务器：" + value + "，请填写可访问的 IPv4 地址（不含端口）");
    }
}
