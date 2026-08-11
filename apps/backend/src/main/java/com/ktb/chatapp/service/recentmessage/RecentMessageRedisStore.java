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
            if redis.call('ZSCORE', KEYS[1], ARGV[2]) == false then
                return -1
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[1])
            return redis.call('ZCARD', KEYS[1]) - 1
            """;

    private static final String APPEND_SCRIPT_SOURCE = """
            local index = 3
            while index <= #ARGV do
                redis.call('ZADD', KEYS[1], ARGV[index + 1], ARGV[index])
                index = index + 2
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return redis.call('ZCARD', KEYS[1])
            """;

    private static final String INITIALIZE_SCRIPT_SOURCE = """
            redis.call('ZADD', KEYS[1], 0, ARGV[1])
            local index = 4
            while index <= #ARGV do
                redis.call('ZADD', KEYS[1], ARGV[index + 1], ARGV[index])
                index = index + 2
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return redis.call('ZCARD', KEYS[1]) - 1
            """;

    private static final String COMPLETE_INITIALIZATION_SCRIPT_SOURCE = """
            redis.call('ZADD', KEYS[1], 0, ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return redis.call('ZCARD', KEYS[1]) - 1
            """;

    private static final DefaultRedisScript<Long> COUNT_SCRIPT =
            new DefaultRedisScript<>(COUNT_SCRIPT_SOURCE, Long.class);

    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then
                return -1
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 1, '(' .. ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return redis.call('ZCARD', KEYS[1]) - 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public OptionalInt count(String roomId, long cutoffMillis) {
        Long result = redisTemplate.execute(
                COUNT_SCRIPT,
                List.of(key(roomId)),
                String.valueOf(cutoffMillis),
                INITIALIZED_MEMBER);
        return toOptionalCount(result);
    }

    /**
     * 각 방의 Lua 집계를 Redis pipeline 한 번으로 전송한다.
     */
    public Map<String, OptionalInt> countAll(Collection<String> roomIds, long cutoffMillis) {
        List<String> orderedRoomIds = new ArrayList<>(roomIds);
        byte[] script = COUNT_SCRIPT_SOURCE.getBytes(StandardCharsets.UTF_8);
        byte[] cutoff = String.valueOf(cutoffMillis).getBytes(StandardCharsets.UTF_8);
        byte[] initializedMember = INITIALIZED_MEMBER.getBytes(StandardCharsets.UTF_8);

        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String roomId : orderedRoomIds) {
                byte[] redisKey = key(roomId).getBytes(StandardCharsets.UTF_8);
                connection.scriptingCommands().eval(
                        script,
                        ReturnType.INTEGER,
                        1,
                        redisKey,
                        cutoff,
                        initializedMember);
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

    public OptionalInt record(
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
        return toOptionalCount(result);
    }

    /**
     * hydration 중인 메시지를 여러 방에 Redis pipeline 한 번으로 추가한다.
     * 완료 marker는 마지막 배치 이후에만 추가하므로 부분 집계는 외부에 노출되지 않는다.
     */
    public void appendAll(
            Map<String, ? extends Collection<RecentMessageEntry>> messagesByRoom,
            long cutoffMillis,
            long ttlSeconds) {
        if (messagesByRoom.isEmpty()) {
            return;
        }

        byte[] script = APPEND_SCRIPT_SOURCE.getBytes(StandardCharsets.UTF_8);
        byte[] cutoff = String.valueOf(cutoffMillis).getBytes(StandardCharsets.UTF_8);
        byte[] ttl = String.valueOf(ttlSeconds).getBytes(StandardCharsets.UTF_8);

        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<String, ? extends Collection<RecentMessageEntry>> room
                    : messagesByRoom.entrySet()) {
                List<byte[]> arguments = new ArrayList<>(3 + room.getValue().size() * 2);
                arguments.add(key(room.getKey()).getBytes(StandardCharsets.UTF_8));
                arguments.add(cutoff);
                arguments.add(ttl);
                for (RecentMessageEntry message : room.getValue()) {
                    arguments.add(message.messageId().getBytes(StandardCharsets.UTF_8));
                    arguments.add(String.valueOf(message.timestampMillis()).getBytes(StandardCharsets.UTF_8));
                }
                connection.scriptingCommands().eval(
                        script,
                        ReturnType.INTEGER,
                        1,
                        arguments.toArray(byte[][]::new));
            }
            return null;
        });

        requirePipelineSize(results, messagesByRoom.size());
    }

    public Map<String, Integer> initializeAll(
            Map<String, ? extends Collection<RecentMessageEntry>> messagesByRoom,
            long cutoffMillis,
            long ttlSeconds) {
        List<String> orderedRoomIds = new ArrayList<>(messagesByRoom.keySet());
        byte[] script = INITIALIZE_SCRIPT_SOURCE.getBytes(StandardCharsets.UTF_8);
        byte[] initializedMember = INITIALIZED_MEMBER.getBytes(StandardCharsets.UTF_8);
        byte[] cutoff = String.valueOf(cutoffMillis).getBytes(StandardCharsets.UTF_8);
        byte[] ttl = String.valueOf(ttlSeconds).getBytes(StandardCharsets.UTF_8);

        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String roomId : orderedRoomIds) {
                Collection<RecentMessageEntry> messages = messagesByRoom.get(roomId);
                List<byte[]> arguments = new ArrayList<>(4 + messages.size() * 2);
                arguments.add(key(roomId).getBytes(StandardCharsets.UTF_8));
                arguments.add(initializedMember);
                arguments.add(cutoff);
                arguments.add(ttl);
                for (RecentMessageEntry message : messages) {
                    arguments.add(message.messageId().getBytes(StandardCharsets.UTF_8));
                    arguments.add(String.valueOf(message.timestampMillis()).getBytes(StandardCharsets.UTF_8));
                }
                connection.scriptingCommands().eval(
                        script,
                        ReturnType.INTEGER,
                        1,
                        arguments.toArray(byte[][]::new));
            }
            return null;
        });

        requirePipelineSize(results, orderedRoomIds.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < orderedRoomIds.size(); index++) {
            counts.put(orderedRoomIds.get(index), requireCount(asLong(results.get(index))));
        }
        return counts;
    }

    /**
     * 여러 배치로 나뉜 hydration이 모두 끝난 방을 pipeline 한 번으로 완료 처리한다.
     */
    public Map<String, Integer> completeInitializationAll(
            Collection<String> roomIds,
            long cutoffMillis,
            long ttlSeconds) {
        List<String> orderedRoomIds = new ArrayList<>(roomIds);
        byte[] script = COMPLETE_INITIALIZATION_SCRIPT_SOURCE.getBytes(StandardCharsets.UTF_8);
        byte[] initializedMember = INITIALIZED_MEMBER.getBytes(StandardCharsets.UTF_8);
        byte[] cutoff = String.valueOf(cutoffMillis).getBytes(StandardCharsets.UTF_8);
        byte[] ttl = String.valueOf(ttlSeconds).getBytes(StandardCharsets.UTF_8);

        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String roomId : orderedRoomIds) {
                connection.scriptingCommands().eval(
                        script,
                        ReturnType.INTEGER,
                        1,
                        key(roomId).getBytes(StandardCharsets.UTF_8),
                        initializedMember,
                        cutoff,
                        ttl);
            }
            return null;
        });

        requirePipelineSize(results, orderedRoomIds.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < orderedRoomIds.size(); index++) {
            counts.put(orderedRoomIds.get(index), requireCount(asLong(results.get(index))));
        }
        return counts;
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

    private void requirePipelineSize(List<Object> results, int expectedSize) {
        if (results.size() != expectedSize) {
            throw new IllegalStateException("Redis recent message pipeline returned an invalid result");
        }
    }

    private String key(String roomId) {
        return KEY_PREFIX + roomId;
    }
}
