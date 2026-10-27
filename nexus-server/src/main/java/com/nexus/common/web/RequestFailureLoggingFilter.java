package com.nexus.common.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RequestFailureLoggingFilter extends OncePerRequestFilter {
    public static final String HANDLED_EXCEPTION_LOGGED = RequestFailureLoggingFilter.class.getName() + ".handledExceptionLogged";

    private static final Logger log = LoggerFactory.getLogger(RequestFailureLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNano = System.nanoTime();
        AtomicBoolean logged = new AtomicBoolean();

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            logEscapedExceptionOnce(
                    request,
                    startNano,
                    ex,
                    logged
            );
            throw ex; // 不吞异常，不在这里擅自改响应
        } finally {
            if (request.isAsyncStarted()) {
                registerLoggingListener(
                        request,
                        response,
                        startNano,
                        logged
                );
            } else {
                logResponseOnce(
                        request,
                        response,
                        startNano,
                        logged
                );
            }
        }
    }
    private void logEscapedExceptionOnce(
            HttpServletRequest request,
            long startNano,
            Throwable exception,
            AtomicBoolean logged
    ) {
        if (!logged.compareAndSet(false, true)) {
            return;
        }

        long durationMs =
                (System.nanoTime() - startNano) / 1_000_000;

        log.error(
                "请求处理抛异常 method={}, route={}, durationMs={}, category={}, exception={}",
                request.getMethod(),
                RequestFailureLogContext.route(request),
                durationMs,
                RequestFailureLogContext.Category.SYSTEM_FAILURE.value(),
                exception.getClass().getSimpleName(),
                exception
        );
    }

    private void logResponseOnce(
            HttpServletRequest request,
            HttpServletResponse response,
            long startNano,
            AtomicBoolean logged
    ) {
        if (Boolean.TRUE.equals(
                request.getAttribute(HANDLED_EXCEPTION_LOGGED))) {
            return;
        }

        int status = response.getStatus();

        // 现在这个 Filter 只记录失败请求。
        if (status < 400) {
            return;
        }

        if (!logged.compareAndSet(false, true)) {
            return;
        }

        long durationMs =
                (System.nanoTime() - startNano) / 1_000_000;

        RequestFailureLogContext.Category category =
                RequestFailureLogContext.category(request);
        String code = RequestFailureLogContext.code(request);
        String route = RequestFailureLogContext.route(request);

        if (category == RequestFailureLogContext.Category.CAPACITY_PROTECTION) {
            log.warn(
                    "请求受容量保护 method={}, route={}, status={}, code={}, category={}, durationMs={}",
                    request.getMethod(), route, status, code, category.value(), durationMs
            );
        } else if (category == RequestFailureLogContext.Category.REQUEST_INTERRUPTED) {
            log.warn(
                    "请求已中断 method={}, route={}, status={}, code={}, category={}, durationMs={}",
                    request.getMethod(), route, status, code, category.value(), durationMs
            );
        } else if (status >= 500) {
            log.error(
                    "请求失败 method={}, route={}, status={}, code={}, category={}, durationMs={}",
                    request.getMethod(),
                    route,
                    status,
                    code,
                    RequestFailureLogContext.Category.SYSTEM_FAILURE.value(),
                    durationMs
            );
        } else {
            log.info(
                    "请求被拒绝 method={}, route={}, status={}, code={}, category={}, durationMs={}",
                    request.getMethod(),
                    route,
                    status,
                    code,
                    RequestFailureLogContext.Category.BUSINESS_REJECTION.value(),
                    durationMs
            );
        }
    }

    private void registerLoggingListener(
            HttpServletRequest request,
            HttpServletResponse response,
            long startNano,
            AtomicBoolean logged
    ) {
        AsyncListener listener = new AsyncListener() {

            @Override
            public void onComplete(AsyncEvent event) {
                logResponseOnce(
                        request,
                        response,
                        startNano,
                        logged
                );
            }

            @Override
            public void onError(AsyncEvent event) {
                logEscapedExceptionOnce(
                        request,
                        startNano,
                        event.getThrowable(),
                        logged
                );
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                /*
                 * ApplicationRateFilter 会取消 Waiter 并 dispatch，
                 * 最终状态交给 onComplete 记录。
                 */
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 如果下游又开始了一轮异步处理，监听器也要进入新周期。
                event.getAsyncContext().addListener(this);
            }
        };

        try {
            request.getAsyncContext().addListener(listener);
        } catch (IllegalStateException exception) {
            /*
             * 异步请求可能恰好已经结束。
             * 尝试按当前最终状态记录，AtomicBoolean 会避免重复。
             */
            logResponseOnce(
                    request,
                    response,
                    startNano,
                    logged
            );
        }
    }
}
