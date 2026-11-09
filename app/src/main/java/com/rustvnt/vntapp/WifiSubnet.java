package com.rustvnt.vntapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取当前 Wi‑Fi（或活跃非 VPN）链路的 IPv4 子网，供可选「公布本机 Wi‑Fi 子网」出栈使用。
 * 非 root 无法做系统级 IP 转发；实际转发依赖 VNT 核心内置 NAT。
 */
final class WifiSubnet {
    record Cidr(String address, int prefix) {
        String value() { return address + "/" + prefix; }
    }

    private WifiSubnet() {}

    /** 优先 Wi‑Fi 传输；没有则回退到当前活跃非 VPN 网络的首个私网 IPv4。 */
    static Cidr detect(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network wifi = findTransport(cm, NetworkCapabilities.TRANSPORT_WIFI);
        Cidr fromWifi = firstIpv4Cidr(cm, wifi, true);
        if (fromWifi != null) return fromWifi;
        Network active = cm.getActiveNetwork();
        if (active == null) return null;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null;
        return firstIpv4Cidr(cm, active, true);
    }

    static List<String> mergeIntoOutput(List<String> existing, Cidr wifi) {
        List<String> result = new ArrayList<>();
        if (existing != null) {
            for (String value : existing) {
                if (value != null && !value.trim().isEmpty()) result.add(value.trim());
            }
        }
        if (wifi == null) return result;
        String target = wifi.value();
        for (String value : result) {
            String cidr = value.split(",", 2)[0].trim();
            if (sameNetwork(cidr, target)) return result;
        }
        result.add(0, target);
        return result;
    }

    private static Network findTransport(ConnectivityManager cm, int transport) {
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(transport)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return network;
            }
        }
        return null;
    }

    private static Cidr firstIpv4Cidr(ConnectivityManager cm, Network network, boolean privateOnly) {
        if (network == null) return null;
        LinkProperties props = cm.getLinkProperties(network);
        if (props == null) return null;
        for (LinkAddress link : props.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (!(address instanceof Inet4Address)) continue;
            if (address.isLoopbackAddress()) continue;
            int prefix = link.getPrefixLength();
            if (prefix < 0 || prefix > 32) continue;
            String host = address.getHostAddress();
            if (host == null) continue;
            if (privateOnly && !isPrivateOrLinkLocal(host)) continue;
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            int value = ipv4(host);
            int networkAddr = value & mask;
            String networkIp = ((networkAddr >>> 24) & 255) + "." + ((networkAddr >>> 16) & 255) + "."
                    + ((networkAddr >>> 8) & 255) + "." + (networkAddr & 255);
            return new Cidr(networkIp, prefix);
        }
        return null;
    }

    private static boolean sameNetwork(String left, String right) {
        try {
            return VpnRouteSet.cidrs(List.of(left)).equals(VpnRouteSet.cidrs(List.of(right)));
        } catch (RuntimeException ignored) {
            return left.equals(right);
        }
    }

    private static boolean isPrivateOrLinkLocal(String host) {
        int value = ipv4(host);
        int b0 = (value >>> 24) & 255;
        int b1 = (value >>> 16) & 255;
        if (b0 == 10) return true;
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
        if (b0 == 192 && b1 == 168) return true;
        if (b0 == 169 && b1 == 254) return true;
        return false;
    }

    private static int ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        int value = 0;
        for (String part : parts) value = (value << 8) | Integer.parseInt(part);
        return value;
    }
}
