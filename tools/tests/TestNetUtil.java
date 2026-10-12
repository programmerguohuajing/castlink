import java.net.NetworkInterface;
import java.util.*;

public class TestNetUtil {
    static String normalizeIp(String ip) {
        String s = ip.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    static Set<String> getAllLocalIps() {
        Set<String> result = new HashSet<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return result;
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host == null || host.contains(":")) continue;
                    result.add(normalizeIp(host));
                }
            }
        } catch (Exception e) { }
        return result;
    }

    static boolean shouldFilter(String deviceIp, Set<String> selfIps) {
        return selfIps.contains(normalizeIp(deviceIp));
    }

    static int passed = 0, failed = 0;
    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        check("normalize plain", normalizeIp("192.168.31.18").equals("192.168.31.18"));
        check("normalize slash", normalizeIp("/192.168.31.18").equals("192.168.31.18"));
        check("normalize spaces", normalizeIp("  192.168.31.18  ").equals("192.168.31.18"));

        Set<String> self = new HashSet<>(Arrays.asList("192.168.31.18", "10.0.0.5"));
        check("filter self exact", shouldFilter("192.168.31.18", self));
        check("filter self slash", shouldFilter("/192.168.31.18", self));
        check("keep other", !shouldFilter("192.168.31.107", self));
        check("keep other slash", !shouldFilter("/192.168.31.107", self));

        Set<String> ips = getAllLocalIps();
        check("ips non-empty", !ips.isEmpty());
        check("no loopback", ips.stream().noneMatch(ip -> ip.startsWith("127.")));
        check("no ipv6", ips.stream().noneMatch(ip -> ip.contains(":")));
        check("no slash", ips.stream().noneMatch(ip -> ip.startsWith("/")));
        System.out.println("Local IPs: " + ips);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
