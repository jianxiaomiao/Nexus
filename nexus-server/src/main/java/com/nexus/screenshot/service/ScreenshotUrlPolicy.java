package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.INVALID_REQUEST;

@Component
public class ScreenshotUrlPolicy {
    private static final Set<String> PAGE_HOSTS = Set.of("blog.csdn.net", "zhuanlan.zhihu.com");
    // 资源域名不自动获得入口页面权限；采用精确主机名，不接受任意后缀。
    private static final Set<String> RESOURCE_HOSTS = Set.of(
            "blog.csdn.net", "zhuanlan.zhihu.com", "csdnimg.cn", "g.csdnimg.cn",
            "i-blog.csdnimg.cn", "img-blog.csdnimg.cn", "static.zhihu.com",
            "pic1.zhimg.com", "pic2.zhimg.com", "pic3.zhimg.com", "pic4.zhimg.com", "pica.zhimg.com", "picx.zhimg.com");

    public URI requirePage(String rawUrl) {
        URI uri = requireHttps(rawUrl);
        if (!PAGE_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new ScreenshotException(INVALID_REQUEST);
        return uri;
    }

    public URI requireResource(String rawUrl) {
        URI uri = requireHttps(rawUrl);
        if (!isAllowedResourceHost(uri.getHost())) throw new ScreenshotException(INVALID_REQUEST);
        return uri;
    }

    public boolean isAllowedResourceHost(String host) {
        return host != null && RESOURCE_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private URI requireHttps(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048) throw new ScreenshotException(INVALID_REQUEST);
        try {
            URI uri = new URI(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null) {
                throw new ScreenshotException(INVALID_REQUEST);
            }
            return uri;
        } catch (URISyntaxException exception) { throw new ScreenshotException(INVALID_REQUEST); }
    }

    public boolean samePage(URI first, URI second) {
        String firstPath = first.getRawPath().isEmpty() ? "/" : first.getRawPath();
        String secondPath = second.getRawPath().isEmpty() ? "/" : second.getRawPath();
        return first.getHost().equalsIgnoreCase(second.getHost()) && firstPath.equals(secondPath)
                && java.util.Objects.equals(first.getRawQuery(), second.getRawQuery());
    }
}
