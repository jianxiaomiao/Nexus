package com.nexus.webExtract.service;

import com.nexus.webExtract.exception.InvalidWebExtractRequestException;
import org.springframework.stereotype.Component;

import com.nexus.common.net.PublicIpAddresses;
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
        return PublicIpAddresses.isPublic(address);
    }
}
