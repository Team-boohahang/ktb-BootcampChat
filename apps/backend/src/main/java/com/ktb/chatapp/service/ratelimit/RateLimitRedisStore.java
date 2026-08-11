package com.ktb.chatapp.service.ratelimit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis fixed-window rate limiter. 카운트 확인, 증가, TTL 설정을 Lua script 한 번으로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class RateLimitRedisStore implements RateLimitStore {

    static final String KEY_PREFIX = "ratelimit:";

    private static final DefaultRedisScript<List> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], 1, 'EX', ARGV[2])
                return {1, tonumber(ARGV[2]), 1}
            end

            local count = tonumber(current)
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
                ttl = tonumber(ARGV[2])
            end

            if count < tonumber(ARGV[1]) then
                count = redis.call('INCR', KEYS[1])
                return {count, ttl, 1}
            end
            return {count, ttl, 0}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public RateLimitState consume(String clientId, int limit, long windowSeconds) {
        List<?> result = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(KEY_PREFIX + clientId),
                String.valueOf(limit),
                String.valueOf(windowSeconds));

        if (result == null || result.size() != 3) {
            throw new IllegalStateException("Redis rate limit script returned an invalid result");
        }

        return new RateLimitState(
                asLong(result.get(0)),
                Math.max(1L, asLong(result.get(1))),
                asLong(result.get(2)) == 1L);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
