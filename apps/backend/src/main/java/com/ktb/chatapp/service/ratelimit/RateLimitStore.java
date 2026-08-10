package com.ktb.chatapp.service.ratelimit;

/**
 * 원자적인 rate limit 카운터 저장소.
 */
public interface RateLimitStore {

    RateLimitState consume(String clientId, int limit, long windowSeconds);

    record RateLimitState(long count, long ttlSeconds, boolean allowed) {
    }
}
