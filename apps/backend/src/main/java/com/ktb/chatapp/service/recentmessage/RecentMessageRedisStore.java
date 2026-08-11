package com.ktb.chatapp.service.recentmessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 최근 30분 메시지 ID를 방별 Redis ZSET으로 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageRedisStore {

    static final String KEY_PREFIX = "recent_messages:room:";
    private static final String INITIALIZED_MEMBER = "__initialized__";

    private static final String COUNT_SCRIPT_SOURCE = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return -1
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[1])
            return redis.call('ZCARD', KEYS[1]) - 1
            """;

    private static final DefaultRedisScript<Long> COUNT_SCRIPT =
            new DefaultRedisScript<>(COUNT_SCRIPT_SOURCE, Long.class);

    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], 0, ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return redis.call('ZCARD', KEYS[1]) - 1
            """, Long.class);

    private static final DefaultRedisScript<Long> INITIALIZE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], 0, ARGV[1])
            local index = 4
            while index <= #ARGV do
                redis.call('ZADD', KEYS[1], ARGV[index + 1], ARGV[index])
                index = index + 2
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return redis.call('ZCARD', KEYS[1]) - 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public OptionalInt count(String roomId, long cutoffMillis) {
        Long result = redisTemplate.execute(
                COUNT_SCRIPT,
                List.of(key(roomId)),
                String.valueOf(cutoffMillis));
        return toOptionalCount(result);
    }

    /**
     * 각 방의 Lua 집계를 Redis pipeline 한 번으로 전송한다.
     */
    public Map<String, OptionalInt> countAll(Collection<String> roomIds, long cutoffMillis) {
        List<String> orderedRoomIds = new ArrayList<>(roomIds);
        byte[] script = COUNT_SCRIPT_SOURCE.getBytes(StandardCharsets.UTF_8);
        byte[] cutoff = String.valueOf(cutoffMillis).getBytes(StandardCharsets.UTF_8);

        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String roomId : orderedRoomIds) {
                byte[] redisKey = key(roomId).getBytes(StandardCharsets.UTF_8);
                connection.scriptingCommands().eval(
                        script,
                        ReturnType.INTEGER,
                        1,
                        redisKey,
                        cutoff);
            }
            return null;
        });

        if (results.size() != orderedRoomIds.size()) {
            throw new IllegalStateException("Redis recent message pipeline returned an invalid result");
        }

        Map<String, OptionalInt> counts = new LinkedHashMap<>();
        for (int index = 0; index < orderedRoomIds.size(); index++) {
            counts.put(orderedRoomIds.get(index), toOptionalCount(asLong(results.get(index))));
        }
        return counts;
    }

    public int record(
            String roomId,
            String messageId,
            long timestampMillis,
            long cutoffMillis,
            long ttlSeconds) {
        Long result = redisTemplate.execute(
                RECORD_SCRIPT,
                List.of(key(roomId)),
                INITIALIZED_MEMBER,
                messageId,
                String.valueOf(timestampMillis),
                String.valueOf(cutoffMillis),
                String.valueOf(ttlSeconds));
        return requireCount(result);
    }

    public int initialize(
            String roomId,
            Collection<RecentMessageEntry> messages,
            long cutoffMillis,
            long ttlSeconds) {
        List<String> arguments = new ArrayList<>(3 + messages.size() * 2);
        arguments.add(INITIALIZED_MEMBER);
        arguments.add(String.valueOf(cutoffMillis));
        arguments.add(String.valueOf(ttlSeconds));
        for (RecentMessageEntry message : messages) {
            arguments.add(message.messageId());
            arguments.add(String.valueOf(message.timestampMillis()));
        }

        Long result = redisTemplate.execute(
                INITIALIZE_SCRIPT,
                List.of(key(roomId)),
                arguments.toArray());
        return requireCount(result);
    }

    private OptionalInt toOptionalCount(Long result) {
        if (result == null) {
            throw new IllegalStateException("Redis recent message script returned no result");
        }
        return result < 0 ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(result));
    }

    private int requireCount(Long result) {
        if (result == null || result < 0) {
            throw new IllegalStateException("Redis recent message script returned an invalid count");
        }
        return Math.toIntExact(result);
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String key(String roomId) {
        return KEY_PREFIX + roomId;
    }
}
