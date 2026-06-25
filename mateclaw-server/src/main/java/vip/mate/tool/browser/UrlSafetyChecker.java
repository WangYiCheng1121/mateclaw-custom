package vip.mate.tool.browser;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SSRF guard — rejects URLs that resolve to loopback, link-local, private, or
 * known cloud-metadata endpoints. Mirrors openfang's {@code check_ssrf} behaviour.
 *
 * <p>Call this before passing any user-controlled URL to the browser or to an
 * outbound HTTP client.
 *
 * <p>防护策略：
 * <ul>
 *   <li>仅允许 http:// 和 https:// 协议</li>
 *   <li>黑名单 hostname 检查（localhost、云元数据域名等）</li>
 *   <li>DNS 解析后 IP 地址检查（回环、内网、链路本地、多播、云元数据 IP）</li>
 *   <li>支持 IPv4 和 IPv6</li>
 *   <li>拦截所有 RFC1918 私有地址段（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16）</li>
 *   <li>拦截 IPv6 内网地址（::1、fe80::/10、fc00::/7 等）</li>
 *   <li>正则二次校验覆盖边缘情况</li>
 * </ul>
 */
public final class UrlSafetyChecker {

    /** Hostnames that must never be reachable via user-supplied URLs. */
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "ip6-localhost",
            "metadata.google.internal",
            "metadata.aws.internal",
            "instance-data",
            "169.254.169.254",     // AWS / Azure / GCP IMDS
            "100.100.100.200",     // Alibaba Cloud IMDS
            "192.0.0.192",         // Azure IMDS alternative
            "0.0.0.0",
            "::1",
            // 额外常见内网 hostname
            "localhost.localdomain",
            "broadcasthost",
            "local"
    );

    /**
     * 内网 IPv4 地址段正则（用于二次校验）
     * - 10.0.0.0/8
     * - 172.16.0.0/12
     * - 192.168.0.0/16
     * - 127.0.0.0/8 (loopback)
     * - 169.254.0.0/16 (link-local)
     * - 0.0.0.0/8
     */
    private static final Pattern PRIVATE_IPV4_PATTERN = Pattern.compile(
            "^(0|10|127)\\..+|" +
            "^169\\.254\\..+|" +
            "^172\\.(1[6-9]|2[0-9]|3[01])\\..+|" +
            "^192\\.168\\..+"
    );

    /**
     * 内网 IPv6 地址段正则
     * - ::1 (loopback)
     * - fe80::/10 (link-local)
     * - fc00::/7 (unique local)
     * - ff00::/8 (multicast)
     */
    private static final Pattern PRIVATE_IPV6_PATTERN = Pattern.compile(
            "^(0{0,4}:){0,7}0{0,3}1$|" +           // ::1
            "^[Ff][Ee]80:|" +                        // fe80::/10
            "^[Ff][Cc]|[Ff][Dd]|" +                  // fc00::/7 - fd00::/7
            "^[Ff][Ff]00:"                           // ff00::/8
    );

    private UrlSafetyChecker() {}

    /**
     * Throw {@link SecurityException} if the URL is unsafe. Accepts http:// and https:// only.
     *
     * @param url the URL to check
     * @throws SecurityException if the URL is unsafe
     */
    public static void check(String url) {
        if (url == null || url.isBlank()) {
            throw new SecurityException("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed URL: " + url);
        }

        // 1. 协议检查
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SecurityException("Only http:// and https:// URLs are allowed (got: " + scheme + ")");
        }

        // 2. Host 检查
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("URL must have a host");
        }

        // 去除 IPv6 的方括号
        String hostname = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;

        // 3. 黑名单 hostname 检查
        if (BLOCKED_HOSTNAMES.contains(hostname.toLowerCase())) {
            throw new SecurityException("SSRF blocked: " + hostname + " is a restricted hostname");
        }

        // 4. DNS 解析 + IP 地址检查
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            if (addresses == null || addresses.length == 0) {
                throw new SecurityException("DNS resolution failed for: " + hostname);
            }

            for (InetAddress addr : addresses) {
                if (isUnsafeAddress(addr)) {
                    throw new SecurityException("SSRF blocked: " + hostname
                            + " resolves to restricted address " + addr.getHostAddress());
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // DNS resolution failure — reject to be safe
            throw new SecurityException("DNS resolution failed for: " + hostname + " — " + e.getMessage());
        }
    }

    /**
     * 二次校验：针对 IP 格式的 URL 直接检查（绕过 DNS 解析）
     * <p>
     * 用于 HTTP 重定向后对目标地址的快速校验
     *
     * @param ip IP 地址字符串
     * @return true 如果地址不安全
     */
    public static boolean isUnsafeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }

        // 检查是否在黑名单中
        if (BLOCKED_HOSTNAMES.contains(ip.toLowerCase())) {
            return true;
        }

        // IPv4 正则检查
        if (PRIVATE_IPV4_PATTERN.matcher(ip).matches()) {
            return true;
        }

        // IPv6 正则检查
        if (PRIVATE_IPV6_PATTERN.matcher(ip).matches()) {
            return true;
        }

        // 尝试解析 InetAddress 做完整检查
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return isUnsafeAddress(addr);
        } catch (Exception e) {
            // 解析失败，保守拦截
            return true;
        }
    }

    /**
     * 检查 InetAddress 是否为不安全的地址
     */
    private static boolean isUnsafeAddress(InetAddress addr) {
        // Java 内置检查
        if (addr.isLoopbackAddress() ||           // 127.0.0.0/8, ::1
            addr.isAnyLocalAddress() ||            // 0.0.0.0, ::
            addr.isLinkLocalAddress() ||           // 169.254.0.0/16, fe80::/10
            addr.isSiteLocalAddress() ||           // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            addr.isMulticastAddress()) {           // 224.0.0.0/4, ff00::/8
            return true;
        }

        // 云元数据 IP 检查
        if (isMetadataIp(addr)) {
            return true;
        }

        // 正则二次校验（覆盖边缘情况）
        String ip = addr.getHostAddress();
        if (ip != null) {
            if (ip.indexOf(':') >= 0) {
                // IPv6
                if (PRIVATE_IPV6_PATTERN.matcher(ip).matches()) {
                    return true;
                }
            } else {
                // IPv4
                if (PRIVATE_IPV4_PATTERN.matcher(ip).matches()) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isMetadataIp(InetAddress addr) {
        String ip = addr.getHostAddress();
        return "169.254.169.254".equals(ip)     // AWS / Azure / GCP IMDS
                || "100.100.100.200".equals(ip)  // Alibaba Cloud IMDS
                || "192.0.0.192".equals(ip)      // Azure IMDS alternative
                || "fd00:ec2::254".equalsIgnoreCase(ip);  // AWS IPv6 IMDS
    }
}
