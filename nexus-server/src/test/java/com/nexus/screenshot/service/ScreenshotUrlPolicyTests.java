package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenshotUrlPolicyTests {
    private final ScreenshotUrlPolicy policy = new ScreenshotUrlPolicy();
    @Test
    void entryAndResourceHostsHaveDifferentPermissions() {
        assertEquals("blog.csdn.net", policy.requirePage("https://blog.csdn.net/article").getHost());
        assertEquals("zhuanlan.zhihu.com", policy.requirePage("https://zhuanlan.zhihu.com/p/1").getHost());
        assertEquals("i-blog.csdnimg.cn", policy.requireResource("https://i-blog.csdnimg.cn/photo.png").getHost());
        assertThrows(ScreenshotException.class, () -> policy.requirePage("https://i-blog.csdnimg.cn/photo.png"));
    }
    @Test
    void refusesUnsafeSchemesHostsPortsCredentialsAndFragments() {
        for (String url : new String[]{"", "https://127.0.0.1/private", "http://blog.csdn.net/a", "file:///etc/passwd",
                "https://blog.csdn.net.evil.example/a", "https://user:pass@blog.csdn.net/a", "https://blog.csdn.net:444/a", "https://blog.csdn.net/a#fragment"}) {
            assertThrows(ScreenshotException.class, () -> policy.requirePage(url), url);
            assertThrows(ScreenshotException.class, () -> policy.requireResource(url), url);
        }
        assertThrows(ScreenshotException.class, () -> policy.requirePage("https://blog.csdn.net/" + "a".repeat(2048)));
    }
    @Test
    void standardPortAndHostCaseDoNotChangeTheRequestedPage() {
        assertTrue(policy.samePage(policy.requirePage("https://BLOG.CSDN.NET:443"), policy.requireResource("https://blog.csdn.net/")));
        assertFalse(policy.samePage(policy.requirePage("https://blog.csdn.net/a?q=1"), policy.requireResource("https://blog.csdn.net/a?q=2")));
    }
}
