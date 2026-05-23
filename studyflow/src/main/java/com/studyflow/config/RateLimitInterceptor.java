package com.studyflow.config;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        for (RateLimitProperties.Rule rule : properties.getRules()) {
            if (matches(rule, path)) {
                check(rule, request);
            }
        }
        return true;
    }

    private boolean matches(RateLimitProperties.Rule rule, String path) {
        return rule.getPattern() != null && path.startsWith(rule.getPattern());
    }

    private void check(RateLimitProperties.Rule rule, HttpServletRequest request) {
        long windowSeconds = Math.max(rule.getWindowSeconds(), 1);
        long capacity = Math.max(rule.getCapacity(), 1);
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        String subject = StpUtil.isLogin() ? "u" + StpUtil.getLoginIdAsString() : "ip" + request.getRemoteAddr();
        String key = "studyflow:rate:" + rule.getPattern().replace("/", ":") + ":" + subject + ":" + window;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 1));
        }
        if (count != null && count > capacity) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
    }
}
