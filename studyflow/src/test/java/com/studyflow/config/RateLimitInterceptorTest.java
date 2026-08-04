package com.studyflow.config;

import com.studyflow.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RedisTokenBucketRateLimiter rateLimiter;

    private RateLimitProperties properties;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setRules(List.of(rule("/api/qa/ask", 20, 60)));
        interceptor = new RateLimitInterceptor(properties, rateLimiter);
    }

    @Test
    void skipsRedisWhenPathDoesNotMatchAnyRule() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void consumesTokenForConfiguredPathAndNestedPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/qa/ask/stream");
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String expectedBucketKey = ":api:qa:ask:ip:203.0.113.10";
        when(rateLimiter.tryConsume(expectedBucketKey, 20, Duration.ofSeconds(60), 1)).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        verify(rateLimiter).tryConsume(expectedBucketKey, 20, Duration.ofSeconds(60), 1);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("20");
        assertThat(response.getHeader("X-RateLimit-Window-Seconds")).isEqualTo("60");
    }

    @Test
    void rejectsWhenRedisBucketHasNoToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/qa/ask");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String expectedBucketKey = ":api:qa:ask:ip:127.0.0.1";
        when(rateLimiter.tryConsume(expectedBucketKey, 20, Duration.ofSeconds(60), 1)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(429);
                    assertThat(ex.getMessage()).isEqualTo("请求过于频繁，请稍后再试");
                });

        verify(rateLimiter).tryConsume(expectedBucketKey, 20, Duration.ofSeconds(60), 1);
    }

    private RateLimitProperties.Rule rule(String pattern, long capacity, long windowSeconds) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setPattern(pattern);
        rule.setCapacity(capacity);
        rule.setWindowSeconds(windowSeconds);
        return rule;
    }
}
