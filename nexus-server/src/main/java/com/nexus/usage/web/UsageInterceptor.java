package com.nexus.usage.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.usage.ApiCode;
import com.nexus.usage.service.UsageRecordService;
import com.nexus.openapi.web.MachineIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class UsageInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(UsageInterceptor.class);

    // 存起始时间的request属性key
    private static final String START_NANO_TIME = "usage.startNanoTime";

    // 新增：请求到达UTC墙上时间
    private static final String START_UTC_TIME = "usage.startUtcTime";

    private final UsageRecordService usageRecordService;

    public UsageInterceptor(UsageRecordService usageRecordService) {
        this.usageRecordService = usageRecordService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 把纳秒开始时间放进request属性，不要写成员变量！线程不安全
        long start = System.nanoTime();
        // ✅ 请求进来那一刻，直接记录UTC墙上时间
        LocalDateTime startUtc = LocalDateTime.now(ZoneOffset.UTC);

        request.setAttribute(START_NANO_TIME, start);
        request.setAttribute(START_UTC_TIME, startUtc);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception exception) throws Exception {
        // afterCompletion：请求完全结束后执行，正常/抛异常都会进来
        // 1. 获取起始时间
        Long startNano = (Long) request.getAttribute(START_NANO_TIME);
        LocalDateTime startUtc = (LocalDateTime) request.getAttribute(START_UTC_TIME);
        if (startNano == null) {
            return;
        }
        long costNs = System.nanoTime() - startNano;

        // 2. 获取ApiKeyIdentity（你认证模块放到request里的身份对象）
        Object identityObj = request.getAttribute(MachineIdentityResolver.ATTRIBUTE_NAME);
        ApiKeyIdentity identity = null;
        if (identityObj instanceof ApiKeyIdentity) {
            identity = (ApiKeyIdentity) identityObj;
        }

        // 3. 判断handler是不是Controller接口，过滤静态资源等非接口请求
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }

        // ========= 关键：根据请求路径+方法匹配 apiCode =========
        // 方式A：可以从注解读取（推荐，Controller方法上加注解标记ApiCode）
        ApiCode apiCode = handlerMethod.getMethodAnnotation(UsageApi.class) != null
                ? handlerMethod.getMethodAnnotation(UsageApi.class).value()
                : null;

        if (apiCode == null) {
            // 不是需要统计的开放接口，直接跳过
            return;
        }

        // 4. 拿到http状态码
        int httpStatus = response.getStatus();

        if (identity == null || startUtc == null) {
            log.warn("跳过用量记录：apiCode={}, identityPresent={}, startUtcPresent={}",
                    apiCode.getCode(), identity != null, startUtc != null);
            return;
        }

        try {
            usageRecordService.record(apiCode, identity, httpStatus, costNs, startUtc, UUID.randomUUID().toString());
        } catch (TaskRejectedException rejected) {
            // 有界队列已满时，只放弃这条统计事件，不改变原本的 API 响应。
            log.warn("用量记录队列已满，事件未提交：apiCode={}, apiKeyId={}, appId={}, reason={}",
                    apiCode.getCode(), identity.apiKeyId(), identity.applicationId(), rejected.getMessage());
        }
    }
}
