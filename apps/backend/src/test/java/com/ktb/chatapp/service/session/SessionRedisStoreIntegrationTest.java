package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SessionRedisStoreIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.8.0-alpine"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private SessionRedisStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new SessionRedisStore(redisTemplate);
    }

    @Test
    void saveAndFind_preserveSessionAndSetThirtyMinuteTtl() {
        long now = System.currentTimeMillis();
        Session session = session("user-1", "session-1", now,
                new SessionMetadata("agent", "127.0.0.1", "device"));

        store.save(session);

        Session found = store.findByUserId("user-1").orElseThrow();
        assertThat(found.getUserId()).isEqualTo("user-1");
        assertThat(found.getSessionId()).isEqualTo("session-1");
        assertThat(found.getCreatedAt()).isEqualTo(now);
        assertThat(found.getLastActivity()).isEqualTo(now);
        assertThat(found.getMetadata()).isEqualTo(session.getMetadata());
        assertThat(redisTemplate.getExpire("session:user:user-1"))
                .isBetween(1L, SessionRedisStore.SESSION_TTL_SECONDS);
    }

    @Test
    void save_overwritesExistingUserSession() {
        long now = System.currentTimeMillis();
        store.save(session("user-1", "old-session", now, null));

        store.save(session("user-1", "new-session", now + 1, null));

        assertThat(store.findByUserId("user-1").orElseThrow().getSessionId())
                .isEqualTo("new-session");
    }

    @Test
    void delete_doesNotRemoveNewSessionWhenOldSessionLogsOut() {
        long now = System.currentTimeMillis();
        store.save(session("user-1", "new-session", now, null));

        store.delete("user-1", "old-session");

        assertThat(store.findByUserId("user-1").orElseThrow().getSessionId())
                .isEqualTo("new-session");
    }

    @Test
    void delete_removesOnlyMatchingSession() {
        store.save(session("user-1", "session-1", System.currentTimeMillis(), null));

        store.delete("user-1", "session-1");

        assertThat(store.findByUserId("user-1")).isEmpty();
    }

    @Test
    void metadata_preservesNullAndEmptyValues() {
        SessionMetadata metadata = new SessionMetadata(null, "", null);
        store.save(session("user-1", "session-1", System.currentTimeMillis(), metadata));

        SessionMetadata found = store.findByUserId("user-1").orElseThrow().getMetadata();

        assertThat(found).isEqualTo(metadata);
    }

    private Session session(String userId, String sessionId, long now, SessionMetadata metadata) {
        return Session.builder()
                .userId(userId)
                .sessionId(sessionId)
                .createdAt(now)
                .lastActivity(now)
                .metadata(metadata)
                .expiresAt(Instant.now().plusSeconds(SessionRedisStore.SESSION_TTL_SECONDS))
                .build();
    }
}
