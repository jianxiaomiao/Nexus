package com.nexus.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RequestFailureLoggingFilter extends OncePerRequestFilter {
    public static final String HANDLED_EXCEPTION_LOGGED = RequestFailureLoggingFilter.class.getName() + ".handledExceptionLogged";

    private static final Logger log = LoggerFactory.getLogger(RequestFailureLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNano = System.nanoTime();
        Throwable escaped = null;

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            escaped = ex;
            throw ex; // 不吞异常，不在这里擅自改响应
        } finally {
            long durationMs = (System.nanoTime() - startNano) / 1_000_000;

            if (escaped != null) {
                log.error("请求处理抛异常 method={}, path={}, durationMs={}",
                        request.getMethod(), request.getRequestURI(), durationMs, escaped);
            } else if (!Boolean.TRUE.equals(request.getAttribute(HANDLED_EXCEPTION_LOGGED))) {
                // MVC 异常已在 GlobalExceptionHandler 记录，避免同一失败重复打印。
                int status = response.getStatus();
                if (status >= 500) {
                    log.error("请求失败 method={}, path={}, status={}, durationMs={}",
                            request.getMethod(), request.getRequestURI(), status, durationMs);
                } else if (status >= 400) {
                    log.info("请求被拒绝 method={}, path={}, status={}, durationMs={}",
                            request.getMethod(), request.getRequestURI(), status, durationMs);
                }
            }
        }
    }
}
