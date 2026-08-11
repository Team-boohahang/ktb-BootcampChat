package com.ktb.chatapp.service.recentmessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RecentMessageRedisStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private RecentMessageRedisStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RecentMessageRedisStore(redisTemplate);
    }

    @Test
    void initializeRecordAndCount_preserveWindowAndDeduplicateMessageId() {
        long now = System.currentTimeMillis();
        long cutoff = now - Duration.ofMinutes(30).toMillis();

        int initialized = store.initialize("room-1", List.of(
                new RecentMessageEntry("expired", cutoff - 1),
                new RecentMessageEntry("boundary", cutoff),
                new RecentMessageEntry("recent", now - 1_000)), cutoff, 1800);
        int afterFirstRecord = store.record("room-1", "new", now, cutoff, 1800);
        int afterDuplicate = store.record("room-1", "new", now, cutoff, 1800);

        assertEquals(2, initialized);
        assertEquals(3, afterFirstRecord);
        assertEquals(3, afterDuplicate);
        assertEquals(OptionalInt.of(3), store.count("room-1", cutoff));
    }

    @Test
    void countAll_returnsMissingAndInitializedRoomsInOnePipeline() {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
        store.initialize("room-1", List.of(), cutoff, 1800);
        store.initialize("room-2", List.of(
                new RecentMessageEntry("message-1", System.currentTimeMillis())), cutoff, 1800);

        Map<String, OptionalInt> result = store.countAll(
                List.of("room-1", "room-2", "room-3"), cutoff);

        assertEquals(OptionalInt.of(0), result.get("room-1"));
        assertEquals(OptionalInt.of(1), result.get("room-2"));
        assertEquals(OptionalInt.empty(), result.get("room-3"));
    }

    @Test
    void record_setsThirtyMinuteTtl() {
        long now = System.currentTimeMillis();
        store.record("room-1", "message-1", now, now - 1_000, 1800);

        Long ttl = redisTemplate.getExpire("recent_messages:room:room-1");
        assertTrue(ttl != null && ttl > 0 && ttl <= 1800);
    }
}
