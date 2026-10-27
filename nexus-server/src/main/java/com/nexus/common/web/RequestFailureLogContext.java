package com.nexus.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 在请求处理链中传递稳定的失败分类和错误码，避免外层日志 Filter
 * 仅凭 HTTP 状态猜测失败性质。
 */
public final class RequestFailureLogContext {
    private static final String CATEGORY_ATTRIBUTE =
            RequestFailureLogContext.class.getName() + ".category";
    private static final String CODE_ATTRIBUTE =
            RequestFailureLogContext.class.getName() + ".code";

    private RequestFailureLogContext() {
    }

    public enum Category {
        BUSINESS_REJECTION("business_rejection"),
        CAPACITY_PROTECTION("capacity_protection"),
        REQUEST_INTERRUPTED("request_interrupted"),
        SYSTEM_FAILURE("system_failure");

        private final String value;

        Category(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static void mark(HttpServletRequest request, Category category, String code) {
        request.setAttribute(CATEGORY_ATTRIBUTE, category);
        request.setAttribute(CODE_ATTRIBUTE, code);
    }

    public static Category category(HttpServletRequest request) {
        Object value = request.getAttribute(CATEGORY_ATTRIBUTE);
        return value instanceof Category category ? category : null;
    }

    public static String code(HttpServletRequest request) {
        Object value = request.getAttribute(CODE_ATTRIBUTE);
        return value instanceof String code && !code.isBlank() ? code : "UNKNOWN";
    }

    /**
     * MVC 已完成映射时记录路由模板；Filter 提前返回时只记录稳定路径组。
     * 绝不退回原始 URI，因为其中可能包含资源 ID 或其他高基数值。
     */
    public static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route && !route.isBlank()) {
            return route;
        }

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        if (path.equals("/v1") || path.startsWith("/v1/")) {
            return "/v1/*";
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            return "/api/*";
        }
        return "UNKNOWN";
    }
}
