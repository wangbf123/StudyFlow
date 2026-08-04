package com.studyflow.config;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long REQUESTED_TOKENS = 1;

    private final RateLimitProperties properties;
    private final RedisTokenBucketRateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            if (matches(rule, path)) {
                check(rule, request, response);
            }
        }
        return true;
    }

    private boolean matches(RateLimitProperties.Rule rule, String path) {
        String pattern = rule.getPattern();
        if (!StringUtils.hasText(pattern)) {
            return false;
        }
        if (path.equals(pattern)) {
            return true;
        }
        return path.startsWith(pattern.endsWith("/") ? pattern : pattern + "/");
    }

    private void check(RateLimitProperties.Rule rule, HttpServletRequest request, HttpServletResponse response) {
        long windowSeconds = Math.max(rule.getWindowSeconds(), 1);
        long capacity = Math.max(rule.getCapacity(), 1);
        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Window-Seconds", String.valueOf(windowSeconds));

        String bucketKey = buildBucketKey(rule, request);
        boolean allowed = rateLimiter.tryConsume(
                bucketKey,
                capacity,
                Duration.ofSeconds(windowSeconds),
                REQUESTED_TOKENS
        );
        if (!allowed) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
    }

    private String buildBucketKey(RateLimitProperties.Rule rule, HttpServletRequest request) {
        String route = normalizeKeyPart(rule.getPattern().replace("/", ":"));
        return route + ":" + resolveSubject(request);
    }

    private String resolveSubject(HttpServletRequest request) {
        try {
            if (StpUtil.isLogin()) {
                return "u:" + normalizeKeyPart(StpUtil.getLoginIdAsString());
            }
        } catch (RuntimeException ignored) {
            // Unit tests and unauthenticated requests can run without a fully initialized Sa-Token context.
        }
        return "ip:" + normalizeKeyPart(resolveClientIp(request));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return StringUtils.hasText(request.getRemoteAddr()) ? request.getRemoteAddr() : "unknown";
    }

    private String normalizeKeyPart(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == ':' || ch == '_' || ch == '-' || ch == '.') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
