package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis implementation of the single-session-per-user store.
 *
 * <p>The whole session is kept in one hash so reads take one Redis command. Saving and setting
 * the TTL are executed atomically to prevent a persistent session key when a process fails
 * between those operations.</p>
 */
@Component
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    static final String KEY_PREFIX = "session:user:";
    static final long SESSION_TTL_SECONDS =
            DurationStyle.detectAndParse(Session.SESSION_TTL).getSeconds();

    private static final String SESSION_ID = "sessionId";
    private static final String CREATED_AT = "createdAt";
    private static final String LAST_ACTIVITY = "lastActivity";
    private static final String METADATA_PRESENT = "metadataPresent";
    private static final String USER_AGENT_PRESENT = "userAgentPresent";
    private static final String USER_AGENT = "userAgent";
    private static final String IP_ADDRESS_PRESENT = "ipAddressPresent";
    private static final String IP_ADDRESS = "ipAddress";
    private static final String DEVICE_INFO_PRESENT = "deviceInfoPresent";
    private static final String DEVICE_INFO = "deviceInfo";

    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1],
                'sessionId', ARGV[1],
                'createdAt', ARGV[2],
                'lastActivity', ARGV[3],
                'metadataPresent', ARGV[4],
                'userAgentPresent', ARGV[5],
                'userAgent', ARGV[6],
                'ipAddressPresent', ARGV[7],
                'ipAddress', ARGV[8],
                'deviceInfoPresent', ARGV[9],
                'deviceInfo', ARGV[10])
            redis.call('EXPIRE', KEYS[1], ARGV[11])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_IF_SESSION_MATCHES_SCRIPT =
            new DefaultRedisScript<>("""
                    local currentSessionId = redis.call('HGET', KEYS[1], 'sessionId')
                    if currentSessionId and currentSessionId == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<Session> findByUserId(String userId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(userId));
        if (values.isEmpty()) {
            return Optional.empty();
        }

        SessionMetadata metadata = readMetadata(values);
        long lastActivity = parseLong(values, LAST_ACTIVITY);
        return Optional.of(Session.builder()
                .userId(userId)
                .sessionId(required(values, SESSION_ID))
                .createdAt(parseLong(values, CREATED_AT))
                .lastActivity(lastActivity)
                .metadata(metadata)
                .expiresAt(Instant.ofEpochMilli(lastActivity).plusSeconds(SESSION_TTL_SECONDS))
                .build());
    }

    @Override
    public Session save(Session session) {
        SessionMetadata metadata = session.getMetadata();
        String userAgent = metadata == null ? null : metadata.userAgent();
        String ipAddress = metadata == null ? null : metadata.ipAddress();
        String deviceInfo = metadata == null ? null : metadata.deviceInfo();

        Long result = redisTemplate.execute(
                SAVE_SCRIPT,
                List.of(key(session.getUserId())),
                session.getSessionId(),
                String.valueOf(session.getCreatedAt()),
                String.valueOf(session.getLastActivity()),
                flag(metadata != null),
                flag(userAgent != null),
                valueOrEmpty(userAgent),
                flag(ipAddress != null),
                valueOrEmpty(ipAddress),
                flag(deviceInfo != null),
                valueOrEmpty(deviceInfo),
                String.valueOf(SESSION_TTL_SECONDS));
        if (result == null || result != 1L) {
            throw new IllegalStateException("Redis session save script returned an invalid result");
        }
        return session;
    }

    @Override
    public void delete(String userId, String sessionId) {
        redisTemplate.execute(
                DELETE_IF_SESSION_MATCHES_SCRIPT,
                List.of(key(userId)),
                sessionId);
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }

    private SessionMetadata readMetadata(Map<Object, Object> values) {
        if (!isPresent(values, METADATA_PRESENT)) {
            return null;
        }
        return new SessionMetadata(
                nullableValue(values, USER_AGENT_PRESENT, USER_AGENT),
                nullableValue(values, IP_ADDRESS_PRESENT, IP_ADDRESS),
                nullableValue(values, DEVICE_INFO_PRESENT, DEVICE_INFO));
    }

    private String nullableValue(Map<Object, Object> values, String presentField, String valueField) {
        return isPresent(values, presentField) ? required(values, valueField) : null;
    }

    private boolean isPresent(Map<Object, Object> values, String field) {
        return "1".equals(required(values, field));
    }

    private long parseLong(Map<Object, Object> values, String field) {
        try {
            return Long.parseLong(required(values, field));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid Redis session field: " + field, exception);
        }
    }

    private String required(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Missing Redis session field: " + field);
        }
        return String.valueOf(value);
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    private String flag(boolean value) {
        return value ? "1" : "0";
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
