package com.rustvnt.vntapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class VpnRouteSet {
    record Route(String address, int prefix) {
        String cidr() { return address + "/" + prefix; }
    }

    private VpnRouteSet() {}

    /**
     * Android VpnService 路由只应包含虚拟网段与入栈/同步到的远端出口网段。
     * 本机「出栈」网段由 VNT 核心公布并做内置 NAT，不能 addRoute，否则会把本机
     * 访问该物理局域网的流量吸进 TUN，破坏出口能力。
     */
    static List<Route> build(String ip, int prefixLen, List<String> inputRoutes,
                             List<String> syncedRoutes) {
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        add(routes, ip + "/" + prefixLen);
        for (String route : inputRoutes) add(routes, route);
        for (String route : syncedRoutes) add(routes, route);
        return new ArrayList<>(routes.values());
    }

    /** @deprecated 旧签名兼容；出栈参数会被忽略。 */
    static List<Route> build(String ip, int prefixLen, List<String> outputRoutes,
                             List<String> inputRoutes, List<String> syncedRoutes) {
        return build(ip, prefixLen, inputRoutes, syncedRoutes);
    }

    static List<Route> rebuild(String ip, int prefixLen, List<String> requestedRoutes) {
        return build(ip, prefixLen, Collections.emptyList(), requestedRoutes);
    }

    static Set<String> cidrs(List<String> routes) {
        Set<String> result = new LinkedHashSet<>();
        for (String route : routes) result.add(parse(route).cidr());
        return result;
    }

    private static void add(LinkedHashMap<String, Route> routes, String value) {
        Route route = parse(value);
        routes.putIfAbsent(route.cidr(), route);
    }

    private static Route parse(String value) {
        String cidr = value == null ? "" : value.split(",", 2)[0].trim();
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("无效路由：" + value);
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("无效路由前缀：" + value, error);
        }
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("无效路由前缀：" + value);
        int address = parseIpv4(parts[0].trim(), value);
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        int network = address & mask;
        String normalized = ((network >>> 24) & 255) + "." + ((network >>> 16) & 255) + "." +
                ((network >>> 8) & 255) + "." + (network & 255);
        return new Route(normalized, prefix);
    }

    private static int parseIpv4(String address, String original) {
        String[] octets = address.split("\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("无效 IPv4 路由：" + original);
        int value = 0;
        for (String octet : octets) {
            int parsed;
            try {
                parsed = Integer.parseInt(octet);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("无效 IPv4 路由：" + original, error);
            }
            if (parsed < 0 || parsed > 255) {
                throw new IllegalArgumentException("无效 IPv4 路由：" + original);
            }
            value = (value << 8) | parsed;
        }
        return value;
    }
}
