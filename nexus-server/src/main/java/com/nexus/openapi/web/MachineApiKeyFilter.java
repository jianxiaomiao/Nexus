package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.common.web.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class MachineApiKeyFilter extends OncePerRequestFilter {
    private final ApiKeyAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    public MachineApiKeyFilter(ApiKeyAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ApiKeyIdentity identity;
        try {
            // 只接受机器凭证；Bearer JWT 即使有效也不能用于 /v1/*。
            String fullKey = extractFullKey(request);
            identity = authenticator.authenticate(fullKey);
        } catch (InvalidApiKeyCredentialException exception) {
            // Filter 在 DispatcherServlet 之前运行，ControllerAdvice 不会处理这里的异常。
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"nexus-openapi\"");
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_API_KEY_CREDENTIAL", exception.getMessage());
            return;
        } catch (ApiKeyForbiddenException exception) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "API_KEY_FORBIDDEN", exception.getMessage());
            return;
        }

        // 身份只保存在这次请求中；Controller 通过 MachineIdentityResolver 读取。
        request.setAttribute(MachineIdentityResolver.ATTRIBUTE_NAME, identity);
        filterChain.doFilter(request, response);
    }

    private String extractFullKey(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (!headers.hasMoreElements()) {
            throw new InvalidApiKeyCredentialException();
        }
        String authorization = headers.nextElement();
        // 重复的 Authorization 请求头含义不明确，直接拒绝。
        if (headers.hasMoreElements()
                || authorization == null
                || !authorization.regionMatches(true, 0, "ApiKey ", 0, 7)) {
            throw new InvalidApiKeyCredentialException();
        }
        String fullKey = authorization.substring(7);
        if (fullKey.isBlank() || fullKey.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidApiKeyCredentialException();
        }
        return fullKey;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(new ApiResponse<>(code, message, null)));
    }
}
