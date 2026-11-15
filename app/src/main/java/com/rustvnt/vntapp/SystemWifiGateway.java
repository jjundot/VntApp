package com.rustvnt.vntapp;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把热点客户端送进与 VpnService 相同的路由表（策略路由表 201），而不是默认全隧道。
 * 表里只有虚拟网段、入栈网段和已同步的对端网段；仅当其中有 0.0.0.0/0 时才安装 default dev tun。
 * 未命中的流量继续走系统共享。热点网段本身仍从热点接口出去。
 */
final class SystemWifiGateway {
    static final int TABLE = 201;
    static final int RULE_PREF = 9999;
    private static final String COMMENT = "vntapp_wifi_gw";
    private static final Pattern IFACE = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,31}");
    private static final Pattern CIDR = Pattern.compile(
            "(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)/(?:[0-9]|[12]\\d|3[0-2])");
    private static volatile boolean applied;

    private SystemWifiGateway() {}

    static synchronized void apply(String tun, List<WifiSubnet.LocalSubnet> hotspots,
                                   List<String> tunCidrs, String dns) throws Exception {
        String script = enableScript(tun, hotspots, tunCidrs, dns);
        Log.i("VNT", "安装热点策略路由\n" + script);
        try {
            logTranscript(RootAccess.runSuScript(script));
        } catch (Exception error) {
            Log.w("VNT", "安装热点策略路由失败: " + error.getMessage());
            throw error;
        } finally {
            applied = true;
        }
    }

    static synchronized void disableQuietly() {
        if (!applied) return;
        String script = teardownScript();
        Log.i("VNT", "清除热点策略路由\n" + script);
        try {
            logTranscript(RootAccess.runSuScript(script));
        } catch (Exception error) {
            Log.w("VNT", "清除热点策略路由失败: " + error.getMessage());
        } finally {
            applied = false;
        }
    }

    /** 第一个落在隧道路由内的 DNS。不在表 201 里的地址不能 DNAT，否则会改写后仍直连出去。 */
    static String coveredDns(List<String> servers, List<String> tunCidrs) {
        if (servers == null || tunCidrs == null) return null;
        for (String server : servers) {
            if (server != null && coveredBy(server, tunCidrs)) return server;
        }
        return null;
    }

    static boolean coveredBy(String ip, List<String> cidrs) {
        int address;
        try {
            address = ipv4(ip);
        } catch (RuntimeException error) {
            return false;
        }
        for (String cidr : cidrs) {
            Parsed parsed = parseCidr(cidr);
            if (parsed == null) continue;
            int mask = parsed.prefix == 0 ? 0 : -1 << (32 - parsed.prefix);
            if ((address & mask) == (parsed.network & mask)) return true;
        }
        return false;
    }

    static String enableScript(String tun, List<WifiSubnet.LocalSubnet> hotspots,
                               List<String> tunCidrs, String dns) {
        String tunName = requireIface(tun, "无效 tun");
        List<WifiSubnet.LocalSubnet> aps = validated(hotspots);
        if (aps.isEmpty()) throw new IllegalArgumentException("没有可接管的热点接口");
        List<String> routes = validatedCidrs(tunCidrs);
        if (dns != null && !coveredBy(dns, routes)) dns = null;
        StringBuilder script = new StringBuilder();
        script.append(shellHeader());
        script.append("vnt \"echo 1 > /proc/sys/net/ipv4/ip_forward\" || exit $?\n");
        script.append("vnt_try \"echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter\"\n");
        script.append("vnt_try \"echo 2 > /proc/sys/net/ipv4/conf/").append(tunName).append("/rp_filter\"\n");
        for (WifiSubnet.LocalSubnet ap : aps) {
            script.append("vnt_try \"echo 2 > /proc/sys/net/ipv4/conf/")
                    .append(ap.name()).append("/rp_filter\"\n");
        }
        script.append(cleanupCalls());
        script.append("vnt \"ip route flush table ").append(TABLE).append("\" || exit $?\n");
        script.append("echo \"VNTCMD del_pref ").append(RULE_PREF).append("\"\n");
        script.append("del_pref ").append(RULE_PREF).append("\n");
        script.append("echo \"VNTEXIT $?\"\n");
        for (String cidr : routes) {
            script.append("vnt \"ip route replace table ").append(TABLE).append(" ")
                    .append(routeDestination(cidr)).append(" dev ").append(tunName)
                    .append("\" || exit $?\n");
        }
        for (WifiSubnet.LocalSubnet ap : aps) {
            script.append("vnt \"ip route replace table ").append(TABLE).append(" ")
                    .append(ap.cidr().value()).append(" dev ").append(ap.name())
                    .append("\" || exit $?\n");
            script.append("vnt \"ip rule add iif ").append(ap.name())
                    .append(" lookup ").append(TABLE).append(" pref ").append(RULE_PREF)
                    .append("\" || exit $?\n");
            script.append("vnt \"iptables -I FORWARD 1 -i ").append(ap.name())
                    .append(" -o ").append(tunName)
                    .append(" -m comment --comment ").append(COMMENT)
                    .append(" -j ACCEPT\" || exit $?\n");
            script.append("vnt \"iptables -I FORWARD 1 -i ").append(tunName)
                    .append(" -o ").append(ap.name())
                    .append(" -m comment --comment ").append(COMMENT)
                    .append(" -j ACCEPT\" || exit $?\n");
            script.append("vnt \"iptables -t nat -A POSTROUTING -s ").append(ap.cidr().value())
                    .append(" -o ").append(tunName)
                    .append(" -m comment --comment ").append(COMMENT)
                    .append(" -j MASQUERADE\" || exit $?\n");
            if (dns != null) {
                script.append("vnt \"iptables -t nat -I PREROUTING 1 -i ").append(ap.name())
                        .append(" -p udp --dport 53 -m comment --comment ").append(COMMENT)
                        .append(" -j DNAT --to-destination ").append(dns).append("\" || exit $?\n");
                script.append("vnt \"iptables -t nat -I PREROUTING 1 -i ").append(ap.name())
                        .append(" -p tcp --dport 53 -m comment --comment ").append(COMMENT)
                        .append(" -j DNAT --to-destination ").append(dns).append("\" || exit $?\n");
            }
        }
        return script.toString();
    }

    static String teardownScript() {
        return shellHeader() + cleanupCalls()
                + "vnt_try \"ip route flush table " + TABLE + "\"\n"
                + "echo \"VNTCMD del_pref " + RULE_PREF + "\"\n"
                + "del_pref " + RULE_PREF + "\n"
                + "echo \"VNTEXIT $?\"\n";
    }

    private static String routeDestination(String cidr) {
        Parsed parsed = parseCidr(cidr);
        if (parsed != null && parsed.prefix == 0) return "default";
        return cidr;
    }

    private static String shellHeader() {
        return """
                vnt() {
                  echo "VNTCMD $1"
                  sh -c "$1" 2>&1
                  code=$?
                  echo "VNTEXIT $code"
                  return $code
                }
                vnt_try() {
                  vnt "$1" || true
                }
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
                del_pref() {
                  n=0
                  while [ "$n" -lt 20 ]; do
                    ip rule del pref "$1" >/dev/null 2>&1 || return 0
                    n=$((n + 1))
                  done
                }
                """;
    }

    private static String cleanupCalls() {
        return """
                echo "VNTCMD cleanup_marked nat POSTROUTING"
                cleanup_marked nat POSTROUTING
                echo "VNTEXIT $?"
                echo "VNTCMD cleanup_marked nat PREROUTING"
                cleanup_marked nat PREROUTING
                echo "VNTEXIT $?"
                echo "VNTCMD cleanup_marked filter FORWARD"
                cleanup_marked filter FORWARD
                echo "VNTEXIT $?"
                """;
    }

    private static void logTranscript(String out) {
        if (out == null || out.isEmpty()) return;
        for (String line : out.split("\n")) {
            if (line.startsWith("VNTEXIT ") && !line.equals("VNTEXIT 0")) Log.w("VNT", line);
            else Log.i("VNT", line);
        }
    }

    private static String requireIface(String name, String message) {
        if (name == null || !IFACE.matcher(name).matches()) {
            throw new IllegalArgumentException(message + "：" + name);
        }
        return name;
    }

    private static List<WifiSubnet.LocalSubnet> validated(List<WifiSubnet.LocalSubnet> subnets) {
        List<WifiSubnet.LocalSubnet> safe = new ArrayList<>();
        if (subnets == null) return safe;
        for (WifiSubnet.LocalSubnet subnet : subnets) {
            if (subnet == null || subnet.kind() != WifiSubnet.Kind.HOTSPOT) continue;
            requireIface(subnet.name(), "无效网卡名");
            if (subnet.cidr() == null || !CIDR.matcher(subnet.cidr().value()).matches()) {
                throw new IllegalArgumentException("无效网段：" + (subnet.cidr() == null ? "" : subnet.cidr().value()));
            }
            safe.add(subnet);
        }
        return safe;
    }

    private static List<String> validatedCidrs(List<String> cidrs) {
        List<String> safe = new ArrayList<>();
        if (cidrs == null) return safe;
        for (String cidr : cidrs) {
            if (cidr == null || !CIDR.matcher(cidr).matches()) {
                throw new IllegalArgumentException("无效隧道路由：" + cidr);
            }
            safe.add(cidr);
        }
        return safe;
    }

    private static Parsed parseCidr(String cidr) {
        if (cidr == null) return null;
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) return null;
        try {
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) return null;
            return new Parsed(ipv4(parts[0]), prefix);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static int ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException(host);
        int value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) throw new IllegalArgumentException(host);
            value = (value << 8) | octet;
        }
        return value;
    }

    private record Parsed(int network, int prefix) {}
}
