package com.nexus.webExtract.service;

import com.nexus.webExtract.exception.InvalidWebExtractRequestException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class WebExtractUrlPolicy {
    // 与短链的“跳转目标”白名单分开维护；这里只允许正文提取实际需要的站点。
    private static final Set<String> ALLOWED_HOSTS = Set.of("zhuanlan.zhihu.com", "blog.csdn.net");

    public URI requireAllowedUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) {
            throw new InvalidWebExtractRequestException("URL 必填且不能超过 2048 个字符");
        }
        try {
            URI uri = new URI(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !isAllowedHost(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawFragment() != null) {
                throw new InvalidWebExtractRequestException("只接受白名单域名的 HTTPS 文章 URL");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new InvalidWebExtractRequestException("URL 格式错误");
        }
    }

    public boolean isAllowedHost(String host) {
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /** 仅允许可在公网路由的地址；DNS 解析与连接必须使用同一次结果。 */
    public boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            return a > 0 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127) // 共享地址空间
                    && !(a == 192 && b == 0 && c == 0) // 特殊用途
                    && !(a == 192 && b == 0 && c == 2) // 文档示例
                    && !(a == 192 && b == 88 && c == 99) // 特殊用途
                    && !(a == 198 && (b == 18 || b == 19)) // 基准测试网段
                    && !(a == 198 && b == 51 && c == 100) // 文档示例
                    && !(a == 203 && b == 0 && c == 113); // 文档示例
        }
        // IPv6 只接受全球单播，拒绝 ULA、链路本地、映射与文档示例地址。
        return address instanceof Inet6Address
                && (Byte.toUnsignedInt(bytes[0]) & 0xe0) == 0x20
                && !(Byte.toUnsignedInt(bytes[0]) == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8);
    }
}
