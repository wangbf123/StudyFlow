package com.studyflow.config;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisTokenBucketRateLimiter {

    private static final String KEY_PREFIX = "studyflow:rate";

    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local tokensKey = KEYS[1]
            local timestampKey = KEYS[2]
            local capacity = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local nowMillis = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttlMillis = tonumber(ARGV[5])

            local tokens = tonumber(redis.call('get', tokensKey))
            if tokens == nil then
                tokens = capacity
            end

            local lastRefreshed = tonumber(redis.call('get', timestampKey))
            if lastRefreshed == nil then
                lastRefreshed = nowMillis
            end

            local delta = math.max(0, nowMillis - lastRefreshed)
            local refill = delta * capacity / windowMillis
            tokens = math.min(capacity, tokens + refill)

            local allowed = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            end

            redis.call('set', tokensKey, tokens, 'PX', ttlMillis)
            redis.call('set', timestampKey, nowMillis, 'PX', ttlMillis)

            return allowed
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public boolean tryConsume(String bucketKey, long capacity, Duration refillWindow, long requestedTokens) {
        long safeCapacity = Math.max(capacity, 1);
        long windowMillis = Math.max(refillWindow.toMillis(), 1);
        long safeRequestedTokens = Math.max(requestedTokens, 1);
        long ttlMillis = Math.max(windowMillis * 2, 1_000);

        String key = KEY_PREFIX + ":{" + bucketKey + "}";
        Long allowed = stringRedisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(key + ":tokens", key + ":timestamp"),
                String.valueOf(safeCapacity),
                String.valueOf(windowMillis),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(safeRequestedTokens),
                String.valueOf(ttlMillis)
        );
        return Long.valueOf(1L).equals(allowed);
    }
}
