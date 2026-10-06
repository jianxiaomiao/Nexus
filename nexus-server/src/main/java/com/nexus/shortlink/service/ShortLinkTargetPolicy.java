package com.nexus.shortlink.service;

import com.nexus.shortlink.exception.InvalidShortLinkRequestException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class ShortLinkTargetPolicy {
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "www.douyin.com", "v.douyin.com", "www.xiaohongshu.com", "weibo.com", "m.weibo.cn"
    );

    public void validate(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank() || originalUrl.length() > 2048) {
            throw new InvalidShortLinkRequestException("原始 URL 必填且不能超过 2048 个字符");
        }
        try {
            URI uri = new URI(originalUrl);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new InvalidShortLinkRequestException("只接受允许域名的 HTTPS URL");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidShortLinkRequestException("原始 URL 格式错误");
        }
    }
}
