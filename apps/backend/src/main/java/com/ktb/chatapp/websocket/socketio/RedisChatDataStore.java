package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private static final String KEY_PREFIX = "socketio:chat:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration stateTtl;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(buildKey(key));
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize Socket.IO chat data for key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            String redisKey = buildKey(key);
            String serializedValue = objectMapper.writeValueAsString(value);
            if (stateTtl == null || stateTtl.isZero() || stateTtl.isNegative()) {
                redisTemplate.opsForValue().set(redisKey, serializedValue);
                return;
            }
            redisTemplate.opsForValue().set(redisKey, serializedValue, stateTtl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Socket.IO chat data for key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(buildKey(key));
    }

    @Override
    public int size(String keyPrefix) {
        RedisCallback<Long> callback = connection -> scanCount(connection, buildKey(keyPrefix) + "*");
        return Math.toIntExact(redisTemplate.execute(callback));
    }

    private String buildKey(String key) {
        return KEY_PREFIX + key;
    }

    private long scanCount(RedisConnection connection, String pattern) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(1000)
                .build();

        long count = 0;
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan Redis keys for pattern=" + pattern, e);
        }
        return count;
    }
}
