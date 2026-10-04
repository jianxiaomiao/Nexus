package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class MachineIdentityResolver {
    // Filter 和 Controller 之间传递身份的请求属性名；只在本次 HTTP 请求内有效。
    public static final String ATTRIBUTE_NAME = MachineIdentityResolver.class.getName() + ".identity";

    public ApiKeyIdentity require(HttpServletRequest request) {
        // Filter 已完成 Secret 和状态校验；这里仅取出当前请求的认证结果。
        Object value = request.getAttribute(ATTRIBUTE_NAME);
        if (value instanceof ApiKeyIdentity identity) {
            return identity;
        }
        // 若 Filter 未执行或属性类型不对，保持拒绝访问。
        throw new InvalidApiKeyCredentialException();
    }
}
