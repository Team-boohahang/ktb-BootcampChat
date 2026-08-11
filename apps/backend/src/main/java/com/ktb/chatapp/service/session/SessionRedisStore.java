package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.service.SessionService.SESSION_TTL_SEC;

@Component
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "session:";
    private static final Duration TTL = Duration.ofSeconds(SESSION_TTL_SEC);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<Session> findByUserId(String userId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, Session.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Session save(Session session) {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(KEY_PREFIX + session.getUserId(), json, TTL);
            return session;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize session for userId=" + session.getUserId(), e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        findByUserId(userId).ifPresent(session -> {
            if (sessionId.equals(session.getSessionId())) {
                redisTemplate.delete(KEY_PREFIX + userId);
            }
        });
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
    }
}