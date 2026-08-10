package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RateLimitService {

    private final RateLimitStore rateLimitStore;
    private final Counter redisErrorCounter;

    public RateLimitService(RateLimitStore rateLimitStore, MeterRegistry meterRegistry) {
        this.rateLimitStore = rateLimitStore;
        this.redisErrorCounter = Counter.builder("ratelimit.redis.errors")
                .description("Redis rate limit operation failures")
                .register(meterRegistry);
    }

    public RateLimitCheckResult checkRateLimit(String clientId, int maxRequests, Duration window) {
        Duration effectiveWindow = window != null ? window : Duration.ofSeconds(1);
        long windowSeconds = Math.max(1L, effectiveWindow.getSeconds());
        long nowEpochSeconds = Instant.now().getEpochSecond();

        try {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("Rate limit clientId must not be blank");
            }

            RateLimitStore.RateLimitState state =
                    rateLimitStore.consume(clientId, maxRequests, windowSeconds);
            long retryAfterSeconds = Math.max(1L, state.ttlSeconds());
            long resetEpochSeconds = nowEpochSeconds + retryAfterSeconds;

            if (!state.allowed()) {
                return RateLimitCheckResult.rejected(
                        maxRequests, windowSeconds, resetEpochSeconds, retryAfterSeconds);
            }

            return RateLimitCheckResult.allowed(
                    maxRequests,
                    Math.max(0, maxRequests - Math.toIntExact(state.count())),
                    windowSeconds,
                    resetEpochSeconds,
                    retryAfterSeconds);
        } catch (Exception e) {
            redisErrorCounter.increment();
            log.error("Redis rate limit check failed for client: {}", clientId, e);
            long resetEpochSeconds = nowEpochSeconds + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests, maxRequests, windowSeconds, resetEpochSeconds, windowSeconds);
        }
    }
}
