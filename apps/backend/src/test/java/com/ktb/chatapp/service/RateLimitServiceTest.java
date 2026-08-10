package com.ktb.chatapp.service;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({RedisTestContainer.class, MongoTestContainer.class})
@TestPropertySource(properties = "socketio.enabled=false")
@DisplayName("Redis RateLimitService 통합 테스트")
class RateLimitServiceTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        var keys = redisTemplate.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("사용자 key에 첫 요청과 TTL을 저장한다")
    void checkRateLimit_CreatesExpectedUserKey() {
        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                "user:user-1", 5, Duration.ofSeconds(60));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(4);
        assertThat(redisTemplate.opsForValue().get("ratelimit:user:user-1")).isEqualTo("1");
        assertThat(redisTemplate.getExpire("ratelimit:user:user-1")).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("한도 횟수까지 허용하고 다음 요청부터 차단하며 count를 고정한다")
    void checkRateLimit_AllowsExactlyLimitRequests() {
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimitService.checkRateLimit(
                    "ip:127.0.0.1", 3, Duration.ofSeconds(30)).allowed()).isTrue();
        }

        RateLimitCheckResult rejected = rateLimitService.checkRateLimit(
                "ip:127.0.0.1", 3, Duration.ofSeconds(30));

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(redisTemplate.opsForValue().get("ratelimit:ip:127.0.0.1")).isEqualTo("3");
    }

    @Test
    @DisplayName("동시 요청에서도 정확히 limit개만 허용한다")
    void checkRateLimit_IsAtomicUnderConcurrency() throws Exception {
        int requestCount = 100;
        int limit = 20;
        try (var executor = Executors.newFixedThreadPool(20)) {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                requests.add(() -> rateLimitService.checkRateLimit(
                        "user:concurrent-user", limit, Duration.ofSeconds(60)).allowed());
            }

            long allowed = executor.invokeAll(requests).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .count();

            assertThat(allowed).isEqualTo(limit);
            assertThat(redisTemplate.opsForValue().get("ratelimit:user:concurrent-user"))
                    .isEqualTo(String.valueOf(limit));
        }
    }

    @Test
    @DisplayName("서로 다른 client key는 독립된 한도를 가진다")
    void checkRateLimit_UsesIndependentKeys() {
        assertThat(rateLimitService.checkRateLimit(
                "user:user-1", 1, Duration.ofSeconds(60)).allowed()).isTrue();
        assertThat(rateLimitService.checkRateLimit(
                "user:user-1", 1, Duration.ofSeconds(60)).allowed()).isFalse();
        assertThat(rateLimitService.checkRateLimit(
                "user:user-2", 1, Duration.ofSeconds(60)).allowed()).isTrue();
    }

    @Test
    @DisplayName("TTL 만료 후 새로운 fixed window를 시작한다")
    void checkRateLimit_StartsNewWindowAfterExpiry() throws Exception {
        assertThat(rateLimitService.checkRateLimit(
                "user:expiring-user", 1, Duration.ofSeconds(1)).allowed()).isTrue();
        assertThat(rateLimitService.checkRateLimit(
                "user:expiring-user", 1, Duration.ofSeconds(1)).allowed()).isFalse();

        Thread.sleep(1_100);

        RateLimitCheckResult nextWindow = rateLimitService.checkRateLimit(
                "user:expiring-user", 1, Duration.ofSeconds(1));
        assertThat(nextWindow.allowed()).isTrue();
        assertThat(redisTemplate.opsForValue().get("ratelimit:user:expiring-user")).isEqualTo("1");
    }
}
