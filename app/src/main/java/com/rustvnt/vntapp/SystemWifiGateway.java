package com.rustvnt.vntapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Root 下按已选本机网段启用内核转发 + NAT，配合 VNT {@code no_nat} 做系统级出口。
 * 不能自动把同一网段上其他设备的默认网关改成手机；那需要改对方 DHCP/静态路由。
 */
final class SystemWifiGateway {
    private static final String COMMENT = "vntapp_wifi_gw";
    private static final Pattern IFACE = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,31}");
    private static final Pattern CIDR = Pattern.compile(
            "(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)/(?:[0-9]|[12]\\d|3[0-2])");
    private static volatile boolean applied;

    private SystemWifiGateway() {}

    static synchronized void enable(List<WifiSubnet.LocalSubnet> subnets) throws Exception {
        RootAccess.runSu(enableScript(subnets));
        applied = true;
    }

    static synchronized void disableQuietly() {
        if (!applied && !RootAccess.isPresent()) return;
        try {
            RootAccess.runSu(cleanupScript());
        } catch (Exception ignored) {
        } finally {
            applied = false;
        }
    }

    static String enableScript(List<WifiSubnet.LocalSubnet> subnets) {
        List<WifiSubnet.LocalSubnet> safe = validated(subnets);
        if (safe.isEmpty()) throw new IllegalArgumentException("没有可接管的本机网段");
        StringBuilder script = new StringBuilder();
        script.append("echo 1 > /proc/sys/net/ipv4/ip_forward\n");
        script.append(cleanupScript());
        for (WifiSubnet.LocalSubnet subnet : safe) {
            String cidr = subnet.cidr().value();
            String iface = subnet.name();
            script.append("iptables -t nat -A POSTROUTING -s ").append(cidr)
                    .append(" -m comment --comment ").append(COMMENT).append(" -j MASQUERADE\n");
            script.append("iptables -I FORWARD 1 -i ").append(iface)
                    .append(" -m comment --comment ").append(COMMENT).append(" -j ACCEPT\n");
            script.append("iptables -I FORWARD 1 -o ").append(iface)
                    .append(" -m comment --comment ").append(COMMENT).append(" -j ACCEPT\n");
        }
        return script.toString();
    }

    static String cleanupScript() {
        return """
                cleanup_marked() {
                  table="$1"
                  chain="$2"
                  n=0
                  while [ "$n" -lt 40 ]; do
                    rule=$(iptables -t "$table" -S "$chain" 2>/dev/null | grep vntapp_wifi_gw | head -n 1 || true)
                    if [ -z "$rule" ]; then
                      break
                    fi
                    del=$(printf '%s\\n' "$rule" | sed 's/^-A/-D/')
                    iptables -t "$table" $del >/dev/null 2>&1 || break
                    n=$((n + 1))
                  done
                }
                cleanup_marked nat POSTROUTING
                cleanup_marked filter FORWARD
                iptables -t nat -D POSTROUTING -m comment --comment vntapp_wifi_gw -j MASQUERADE >/dev/null 2>&1 || true
                iptables -D FORWARD -m comment --comment vntapp_wifi_gw -j ACCEPT >/dev/null 2>&1 || true
                """;
    }

    private static List<WifiSubnet.LocalSubnet> validated(List<WifiSubnet.LocalSubnet> subnets) {
        List<WifiSubnet.LocalSubnet> safe = new ArrayList<>();
        if (subnets == null) return safe;
        for (WifiSubnet.LocalSubnet subnet : subnets) {
            if (subnet == null || subnet.name() == null || subnet.cidr() == null) continue;
            if (!IFACE.matcher(subnet.name()).matches()) {
                throw new IllegalArgumentException("无效网卡名：" + subnet.name());
            }
            if (!CIDR.matcher(subnet.cidr().value()).matches()) {
                throw new IllegalArgumentException("无效网段：" + subnet.cidr().value());
            }
            safe.add(subnet);
        }
        return safe;
    }
}
