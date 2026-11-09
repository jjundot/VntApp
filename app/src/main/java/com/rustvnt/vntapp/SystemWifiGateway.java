package com.rustvnt.vntapp;

/**
 * Root 下启用内核转发 + NAT，配合 VNT {@code no_nat} 做系统级点对网出口。
 * 不能自动把同一 Wi‑Fi 上其他设备的默认网关改成手机；那需要改对方 DHCP/静态路由。
 */
final class SystemWifiGateway {
    private static final String COMMENT = "vntapp_wifi_gw";
    private static volatile boolean applied;

    private SystemWifiGateway() {}

    static synchronized void enable() throws Exception {
        RootAccess.runSu(
                "echo 1 > /proc/sys/net/ipv4/ip_forward; "
                        + "iptables -t nat -C POSTROUTING -m comment --comment " + COMMENT
                        + " -j MASQUERADE >/dev/null 2>&1 || "
                        + "iptables -t nat -A POSTROUTING -m comment --comment " + COMMENT
                        + " -j MASQUERADE; "
                        + "iptables -C FORWARD -m comment --comment " + COMMENT
                        + " -j ACCEPT >/dev/null 2>&1 || "
                        + "iptables -I FORWARD 1 -m comment --comment " + COMMENT + " -j ACCEPT");
        applied = true;
    }

    static synchronized void disableQuietly() {
        if (!applied && !RootAccess.isPresent()) return;
        try {
            RootAccess.runSu(
                    "iptables -t nat -D POSTROUTING -m comment --comment " + COMMENT
                            + " -j MASQUERADE >/dev/null 2>&1 || true; "
                            + "iptables -D FORWARD -m comment --comment " + COMMENT
                            + " -j ACCEPT >/dev/null 2>&1 || true");
        } catch (Exception ignored) {
        } finally {
            applied = false;
        }
    }
}
