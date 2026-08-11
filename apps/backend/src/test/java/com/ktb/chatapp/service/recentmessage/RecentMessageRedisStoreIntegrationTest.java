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

        List<RecentMessageEntry> initialEntries = List.of(
                new RecentMessageEntry("expired", cutoff - 1),
                new RecentMessageEntry("boundary", cutoff),
                new RecentMessageEntry("recent", now - 1_000));
        int initialized = store.initializeAll(
                Map.of("room-1", initialEntries), cutoff, 1800).get("room-1");
        OptionalInt afterFirstRecord = store.record("room-1", "new", now, cutoff, 1800);
        OptionalInt afterDuplicate = store.record("room-1", "new", now, cutoff, 1800);

        assertEquals(2, initialized);
        assertEquals(OptionalInt.of(3), afterFirstRecord);
        assertEquals(OptionalInt.of(3), afterDuplicate);
        assertEquals(OptionalInt.of(3), store.count("room-1", cutoff));
    }

    @Test
    void countAll_returnsMissingAndInitializedRoomsInOnePipeline() {
        long cutoff = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
        store.initializeAll(Map.of(
                "room-1", List.of(),
                "room-2", List.of(
                        new RecentMessageEntry("message-1", System.currentTimeMillis()))), cutoff, 1800);

        Map<String, OptionalInt> result = store.countAll(
                List.of("room-1", "room-2", "room-3"), cutoff);

        assertEquals(OptionalInt.of(0), result.get("room-1"));
        assertEquals(OptionalInt.of(1), result.get("room-2"));
        assertEquals(OptionalInt.empty(), result.get("room-3"));
    }

    @Test
    void appendAll_hidesPartialHydrationUntilInitializeAllCompletes() {
        long now = System.currentTimeMillis();
        long cutoff = now - Duration.ofMinutes(30).toMillis();

        store.appendAll(Map.of(
                "room-1", List.of(new RecentMessageEntry("message-1", now)),
                "room-2", List.of(new RecentMessageEntry("message-2", now))), cutoff, 1800);

        Map<String, OptionalInt> partial = store.countAll(List.of("room-1", "room-2"), cutoff);
        assertEquals(OptionalInt.empty(), partial.get("room-1"));
        assertEquals(OptionalInt.empty(), partial.get("room-2"));

        Map<String, Integer> initialized =
                store.completeInitializationAll(List.of("room-1", "room-2"), cutoff, 1800);

        assertEquals(Map.of("room-1", 1, "room-2", 1), initialized);
        assertEquals(OptionalInt.of(1), store.count("room-1", cutoff));
        assertEquals(OptionalInt.of(1), store.count("room-2", cutoff));
    }

    @Test
    void record_setsThirtyMinuteTtl() {
        long now = System.currentTimeMillis();
        store.initializeAll(Map.of("room-1", List.of()), now - 1_000, 1800);
        store.record("room-1", "message-1", now, now - 1_000, 1800);

        Long ttl = redisTemplate.getExpire("recent_messages:room:room-1");
        assertTrue(ttl != null && ttl > 0 && ttl <= 1800);
    }

    @Test
    void record_doesNotRecreateInitializedMarkerAfterKeyExpires() {
        long now = System.currentTimeMillis();
        long cutoff = now - Duration.ofMinutes(30).toMillis();
        store.initializeAll(
                Map.of("room-1", List.of(new RecentMessageEntry("existing", now - 1_000))),
                cutoff,
                1800);
        redisTemplate.delete("recent_messages:room:room-1");

        OptionalInt result = store.record("room-1", "new", now, cutoff, 1800);

        assertEquals(OptionalInt.empty(), result);
        assertEquals(Boolean.FALSE, redisTemplate.hasKey("recent_messages:room:room-1"));
    }
}
