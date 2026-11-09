package com.rustvnt.vntapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 枚举本机 IPv4 网段，供「公布本机子网」选择。热点子网在 Android 11+ 每次开启都可能变化，
 * 因此选择同时记下接口名、类型和当时的 CIDR；启动时再按类型或原 CIDR 重新匹配。
 */
final class WifiSubnet {
    static final String CONFIG_KEY = "advertise_subnets";

    enum Kind {
        HOTSPOT("hotspot", "热点"),
        WIFI("wifi", "Wi‑Fi"),
        USB("usb", "USB 共享"),
        ETHERNET("ethernet", "以太网"),
        CELLULAR("cellular", "蜂窝"),
        OTHER("other", "其他");

        final String id;
        final String label;

        Kind(String id, String label) {
            this.id = id;
            this.label = label;
        }

        static Kind fromId(String id) {
            if (id == null) return OTHER;
            for (Kind kind : values()) if (kind.id.equalsIgnoreCase(id.trim())) return kind;
            return OTHER;
        }
    }

    record Cidr(String address, int prefix) {
        String value() { return address + "/" + prefix; }
    }

    /** 当前枚举到的一块本机网段。 */
    record LocalSubnet(String name, Kind kind, Cidr cidr) {
        Selection selection() { return new Selection(name, kind, cidr.value()); }
        String rowLabel() { return kind.label + "  ·  " + name + "  ·  " + cidr.value(); }
    }

    /** 配置里记住的一条选择。{@code cidr} 是保存时的网段，启动时可能已变化。 */
    record Selection(String name, Kind kind, String cidr) {
        String describe() { return kind.label + " " + name + " " + cidr; }
    }

    record Resolution(List<LocalSubnet> matched, List<String> skipped) {}

    private WifiSubnet() {}

    static List<LocalSubnet> enumerate(Context context) {
        return enumerateInterfaces(safeInterfaces(), interfaceHints(context));
    }

    static List<Selection> readSelections(JSONObject config) {
        if (config == null || !config.has(CONFIG_KEY) || config.isNull(CONFIG_KEY)) return null;
        JSONArray array = config.optJSONArray(CONFIG_KEY);
        List<Selection> selections = new ArrayList<>();
        if (array == null) return selections;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim();
            String cidr = item.optString("cidr", "").trim();
            if (name.isEmpty() || cidr.isEmpty()) continue;
            selections.add(new Selection(name, Kind.fromId(item.optString("kind", "")), cidr));
        }
        return selections;
    }

    static void writeSelections(JSONObject config, List<Selection> selections) throws Exception {
        if (selections == null) return;
        JSONArray array = new JSONArray();
        for (Selection selection : selections) {
            array.put(new JSONObject()
                    .put("name", selection.name)
                    .put("kind", selection.kind.id)
                    .put("cidr", selection.cidr));
        }
        config.put(CONFIG_KEY, array);
    }

    static String summary(List<Selection> saved, List<LocalSubnet> present) {
        if (saved == null) {
            Resolution resolution = resolve(null, present, true);
            if (resolution.matched.isEmpty()) return "默认：优先热点，否则 Wi‑Fi（当前未检测到）";
            return "默认：" + resolution.matched.get(0).rowLabel();
        }
        if (saved.isEmpty()) return "未选择网段";
        StringBuilder text = new StringBuilder();
        for (Selection selection : saved) {
            if (text.length() > 0) text.append("、");
            text.append(selection.describe());
        }
        return text.toString();
    }

    /** 对话框初次勾选：未保存过选择时勾上默认热点/Wi‑Fi，否则勾上仍能匹配到的接口。 */
    static List<String> checkedNames(List<Selection> saved, List<LocalSubnet> present) {
        List<String> names = new ArrayList<>();
        if (saved == null) {
            for (LocalSubnet subnet : resolve(null, present, true).matched) names.add(subnet.name);
            return names;
        }
        for (Selection selection : saved) {
            LocalSubnet matched = match(selection, present);
            if (matched != null && !names.contains(matched.name)) names.add(matched.name);
        }
        return names;
    }

    /**
     * {@code saved == null} 且 {@code advertise} 时沿用旧配置：有热点用热点，否则用 Wi‑Fi 客户端。
     * 显式空列表表示用户清空了选择，不再回退。
     */
    static Resolution resolve(List<Selection> saved, List<LocalSubnet> present, boolean advertise) {
        List<LocalSubnet> available = present == null ? List.of() : present;
        if (saved == null) {
            if (!advertise) return new Resolution(List.of(), List.of());
            LocalSubnet hotspot = first(available, Kind.HOTSPOT);
            if (hotspot != null) return new Resolution(List.of(hotspot), List.of());
            LocalSubnet wifi = first(available, Kind.WIFI);
            if (wifi != null) return new Resolution(List.of(wifi), List.of());
            return new Resolution(List.of(), List.of("默认网段（热点或 Wi‑Fi）当前不存在"));
        }
        List<LocalSubnet> matched = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Selection selection : saved) {
            LocalSubnet found = match(selection, available);
            if (found == null) {
                skipped.add(selection.describe());
                continue;
            }
            if (!containsSame(matched, found)) matched.add(found);
        }
        return new Resolution(matched, skipped);
    }

    static LocalSubnet match(Selection selection, List<LocalSubnet> present) {
        if (selection == null || present == null) return null;
        for (LocalSubnet subnet : present) {
            if (subnet.name.equals(selection.name)) return subnet;
        }
        if (selection.kind != Kind.OTHER) {
            for (LocalSubnet subnet : present) {
                if (subnet.kind == selection.kind) return subnet;
            }
        }
        for (LocalSubnet subnet : present) {
            if (sameNetwork(subnet.cidr.value(), selection.cidr)) return subnet;
        }
        return null;
    }

    static List<String> mergeIntoOutput(List<String> existing, Cidr extra) {
        return mergeIntoOutput(existing, extra == null ? List.of() : List.of(extra));
    }

    static List<String> mergeIntoOutput(List<String> existing, List<Cidr> extras) {
        List<String> result = new ArrayList<>();
        if (existing != null) {
            for (String value : existing) {
                if (value != null && !value.trim().isEmpty()) result.add(value.trim());
            }
        }
        if (extras == null || extras.isEmpty()) return result;
        List<String> prefix = new ArrayList<>();
        for (Cidr extra : extras) {
            if (extra == null) continue;
            String target = extra.value();
            if (containsNetwork(result, target) || containsNetwork(prefix, target)) continue;
            prefix.add(target);
        }
        prefix.addAll(result);
        return prefix;
    }

    static Cidr networkCidr(String host, int prefix) {
        if (host == null || prefix < 0 || prefix > 32) return null;
        int ip;
        try {
            ip = ipv4(host.trim());
        } catch (RuntimeException error) {
            return null;
        }
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        int network = ip & mask;
        return new Cidr(format(network), prefix);
    }

    /** {@code hint} 来自 ConnectivityManager；已被判定为热点/共享的名字优先于传输类型。 */
    static Kind classify(String name, Kind hint) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (hint == Kind.HOTSPOT || isHotspotName(normalized)) return Kind.HOTSPOT;
        if (isWifiClientName(normalized)) return Kind.WIFI;
        if (isUsbName(normalized)) return Kind.USB;
        if (normalized.startsWith("eth")) return Kind.ETHERNET;
        if (isCellularName(normalized)) return Kind.CELLULAR;
        if (hint != null && hint != Kind.OTHER) return hint;
        return Kind.OTHER;
    }

    static boolean isTun(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith("tun");
    }

    private static List<LocalSubnet> enumerateInterfaces(Enumeration<NetworkInterface> interfaces,
                                                         Map<String, Kind> hints) {
        List<LocalSubnet> result = new ArrayList<>();
        if (interfaces == null) return result;
        while (interfaces.hasMoreElements()) {
            NetworkInterface nif = interfaces.nextElement();
            String name = nif.getName();
            if (name == null || isTun(name)) continue;
            try {
                if (nif.isLoopback() || !nif.isUp()) continue;
            } catch (SocketException error) {
                continue;
            }
            Kind kind = classify(name, hints.get(name));
            for (InterfaceAddress binding : nif.getInterfaceAddresses()) {
                InetAddress address = binding.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                String host = address.getHostAddress();
                Cidr cidr = networkCidr(host, binding.getNetworkPrefixLength());
                if (cidr == null) continue;
                result.add(new LocalSubnet(name, kind, cidr));
            }
        }
        result.sort(Comparator
                .comparingInt((LocalSubnet subnet) -> subnet.kind.ordinal())
                .thenComparing(subnet -> subnet.name)
                .thenComparing(subnet -> subnet.cidr.value()));
        return result;
    }

    private static Enumeration<NetworkInterface> safeInterfaces() {
        try {
            return NetworkInterface.getNetworkInterfaces();
        } catch (SocketException error) {
            return Collections.emptyEnumeration();
        }
    }

    private static Map<String, Kind> interfaceHints(Context context) {
        Map<String, Kind> hints = new HashMap<>();
        if (context == null) return hints;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return hints;
        for (String name : tetheredInterfaces(cm)) hints.put(name, Kind.HOTSPOT);
        try {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                LinkProperties props = cm.getLinkProperties(network);
                if (caps == null || props == null || props.getInterfaceName() == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                String name = props.getInterfaceName();
                if (hints.containsKey(name) || isTun(name)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) hints.put(name, Kind.WIFI);
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) hints.put(name, Kind.CELLULAR);
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) hints.put(name, Kind.ETHERNET);
                else if (Build.VERSION.SDK_INT >= 31
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
                    hints.put(name, Kind.USB);
                }
            }
        } catch (RuntimeException ignored) {
            // 枚举失败时仍按接口名分类。
        }
        return hints;
    }

    private static List<String> tetheredInterfaces(ConnectivityManager cm) {
        try {
            Object value = ConnectivityManager.class.getMethod("getTetheredIfaces").invoke(cm);
            if (value instanceof String[] names) {
                List<String> result = new ArrayList<>();
                for (String name : names) if (name != null && !name.isEmpty()) result.add(name);
                return result;
            }
        } catch (Exception ignored) {
            // 隐藏 API，普通应用上经常不可用。
        }
        return List.of();
    }

    private static boolean isHotspotName(String name) {
        if (name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan")) return true;
        if (name.contains("softap")) return true;
        if (!name.startsWith("wlan")) return false;
        String rest = name.substring(4);
        return !rest.isEmpty() && !rest.equals("0") && !rest.startsWith("0.");
    }

    private static boolean isWifiClientName(String name) {
        return name.equals("wlan0") || name.startsWith("wlan0.") || name.equals("wifi0");
    }

    private static boolean isUsbName(String name) {
        return name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm");
    }

    private static boolean isCellularName(String name) {
        return name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                || name.startsWith("wwan") || name.startsWith("ccinet") || name.startsWith("clat");
    }

    private static LocalSubnet first(List<LocalSubnet> present, Kind kind) {
        for (LocalSubnet subnet : present) if (subnet.kind == kind) return subnet;
        return null;
    }

    private static boolean containsSame(List<LocalSubnet> matched, LocalSubnet candidate) {
        for (LocalSubnet subnet : matched) {
            if (subnet.name.equals(candidate.name) && subnet.cidr.value().equals(candidate.cidr.value())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNetwork(List<String> routes, String cidr) {
        for (String value : routes) {
            String existing = value.split(",", 2)[0].trim();
            if (sameNetwork(existing, cidr)) return true;
        }
        return false;
    }

    private static boolean sameNetwork(String left, String right) {
        try {
            return VpnRouteSet.cidrs(List.of(left)).equals(VpnRouteSet.cidrs(List.of(right)));
        } catch (RuntimeException ignored) {
            return left.equals(right);
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

    private static String format(int network) {
        return ((network >>> 24) & 255) + "." + ((network >>> 16) & 255) + "."
                + ((network >>> 8) & 255) + "." + (network & 255);
    }
}
