package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitStore.RateLimitState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String CLIENT_ID = "user:user-1";

    @Mock
    private RateLimitStore rateLimitStore;

    private SimpleMeterRegistry meterRegistry;
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        rateLimitService = new RateLimitService(rateLimitStore, meterRegistry);
    }

    @Test
    @DisplayName("허용된 요청은 Redis 상태로 남은 횟수와 reset 정보를 계산한다")
    void checkRateLimit_Allowed() {
        when(rateLimitStore.consume(CLIENT_ID, 3, 30))
                .thenReturn(new RateLimitState(1, 25, true));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(25);
        verify(rateLimitStore).consume(CLIENT_ID, 3, 30);
    }

    @Test
    @DisplayName("저장소가 차단으로 판정하면 남은 횟수 0과 TTL을 반환한다")
    void checkRateLimit_Rejected() {
        when(rateLimitStore.consume(CLIENT_ID, 3, 30))
                .thenReturn(new RateLimitState(3, 10, false));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("0초 및 null window는 최소 1초로 정규화한다")
    void checkRateLimit_InvalidWindows_NormalizeToOneSecond() {
        when(rateLimitStore.consume(CLIENT_ID, 3, 1))
                .thenReturn(new RateLimitState(1, 1, true));

        assertThat(rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO).windowSeconds())
                .isEqualTo(1);
        assertThat(rateLimitService.checkRateLimit(CLIENT_ID, 3, null).windowSeconds())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Redis 실패 시 요청을 허용하고 오류 메트릭을 증가시킨다")
    void checkRateLimit_StoreFailure_FailsOpen() {
        when(rateLimitStore.consume(CLIENT_ID, 3, 30))
                .thenThrow(new IllegalStateException("redis down"));

        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
        assertThat(meterRegistry.counter("ratelimit.redis.errors").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("clientId가 없으면 Redis를 호출하지 않고 fail-open 처리한다")
    void checkRateLimit_BlankClientId_FailsOpenWithoutStoreCall() {
        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(meterRegistry.counter("ratelimit.redis.errors").count()).isEqualTo(1);
        verify(rateLimitStore, never()).consume(anyString(), anyInt(), anyLong());
    }
}
