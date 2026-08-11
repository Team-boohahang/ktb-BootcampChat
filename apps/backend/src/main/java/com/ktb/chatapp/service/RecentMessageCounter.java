package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.service.recentmessage.RecentMessageEntry;
import com.ktb.chatapp.service.recentmessage.RecentMessageMongoStore;
import com.ktb.chatapp.service.recentmessage.RecentMessageRedisStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final RecentMessageMongoStore mongoStore;
    private final RecentMessageRedisStore redisStore;

    public int countRecentMessages(String roomId) {
        if (roomId == null) {
            return 0;
        }
        return countRecentMessages(List.of(roomId)).getOrDefault(roomId, 0);
    }

    public Map<String, Integer> countRecentMessages(Collection<String> roomIds) {
        Set<String> uniqueRoomIds = new LinkedHashSet<>();
        for (String roomId : roomIds) {
            if (roomId != null) {
                uniqueRoomIds.add(roomId);
            }
        }
        if (uniqueRoomIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Window window = currentWindow();
        try {
            Map<String, OptionalInt> redisCounts = redisStore.countAll(uniqueRoomIds, window.cutoffMillis());
            Set<String> missingRoomIds = new LinkedHashSet<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            redisCounts.forEach((roomId, count) -> {
                if (count.isPresent()) {
                    counts.put(roomId, count.getAsInt());
                } else {
                    missingRoomIds.add(roomId);
                }
            });

            if (!missingRoomIds.isEmpty()) {
                counts.putAll(hydrateMissingRooms(missingRoomIds, window));
            }
            return orderedCounts(uniqueRoomIds, counts);
        } catch (RuntimeException cacheFailure) {
            log.warn("Recent message cache failed; falling back to MongoDB aggregation", cacheFailure);
            return mongoCounts(uniqueRoomIds, window.since());
        }
    }

    public int recordMessage(Message message) {
        if (message == null || message.getRoomId() == null || message.getId() == null
                || message.getTimestamp() == null) {
            throw new IllegalArgumentException("Saved message id, roomId and timestamp are required");
        }

        Window window = currentWindow();
        try {
            RecentMessageEntry entry = requireEntry(message);
            OptionalInt recordedCount = record(entry, message.getRoomId(), window);
            if (recordedCount.isPresent()) {
                return recordedCount.getAsInt();
            }

            hydrateMissingRooms(Set.of(message.getRoomId()), window);
            return record(entry, message.getRoomId(), window)
                    .orElseGet(() -> mongoCounts(List.of(message.getRoomId()), window.since())
                            .getOrDefault(message.getRoomId(), 0));
        } catch (RuntimeException redisFailure) {
            log.warn("Redis recent message record failed; falling back to MongoDB: roomId={}",
                    message.getRoomId(), redisFailure);
            return mongoCounts(List.of(message.getRoomId()), window.since())
                    .getOrDefault(message.getRoomId(), 0);
        }
    }

    private OptionalInt record(RecentMessageEntry entry, String roomId, Window window) {
        return redisStore.record(
                roomId,
                entry.messageId(),
                entry.timestampMillis(),
                window.cutoffMillis(),
                RECENT_WINDOW.toSeconds());
    }

    private Map<String, Integer> hydrateMissingRooms(Set<String> roomIds, Window window) {
        Map<String, List<RecentMessageEntry>> batch = new LinkedHashMap<>();
        int batchSize = 0;
        boolean appendedBatch = false;

        try (Stream<Message> messages = mongoStore.streamRecentMessages(roomIds, window.since())) {
            var iterator = messages.iterator();
            while (iterator.hasNext()) {
                Message message = iterator.next();
                Optional<RecentMessageEntry> entry = toEntry(message);
                if (entry.isEmpty()) {
                    continue;
                }
                batch.computeIfAbsent(message.getRoomId(), ignored -> new java.util.ArrayList<>())
                        .add(entry.get());
                batchSize++;
                if (batchSize == RecentMessageMongoStore.HYDRATION_BATCH_SIZE
                        && iterator.hasNext()) {
                    appendBatch(batch, window);
                    batch.clear();
                    batchSize = 0;
                    appendedBatch = true;
                }
            }
        }

        if (!appendedBatch) {
            Map<String, Collection<RecentMessageEntry>> messagesByRoom = new LinkedHashMap<>();
            for (String roomId : roomIds) {
                messagesByRoom.put(roomId, batch.getOrDefault(roomId, List.of()));
            }
            return redisStore.initializeAll(
                    messagesByRoom, window.cutoffMillis(), RECENT_WINDOW.toSeconds());
        }

        if (!batch.isEmpty()) {
            appendBatch(batch, window);
        }
        return redisStore.completeInitializationAll(
                roomIds, window.cutoffMillis(), RECENT_WINDOW.toSeconds());
    }

    private void appendBatch(Map<String, List<RecentMessageEntry>> batch, Window window) {
        redisStore.appendAll(
                new LinkedHashMap<>(batch),
                window.cutoffMillis(),
                RECENT_WINDOW.toSeconds());
    }

    private Map<String, Integer> mongoCounts(Collection<String> roomIds, LocalDateTime since) {
        Map<String, Integer> aggregatedCounts = mongoStore.countAll(roomIds, since);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String roomId : roomIds) {
            counts.put(roomId, aggregatedCounts.getOrDefault(roomId, 0));
        }
        return counts;
    }

    private Map<String, Integer> orderedCounts(
            Collection<String> roomIds,
            Map<String, Integer> counts) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String roomId : roomIds) {
            ordered.put(roomId, counts.getOrDefault(roomId, 0));
        }
        return ordered;
    }

    private Optional<RecentMessageEntry> toEntry(Message message) {
        if (message == null || message.getId() == null || message.getRoomId() == null
                || message.getTimestamp() == null) {
            return Optional.empty();
        }
        return Optional.of(new RecentMessageEntry(
                message.getId(), toEpochMillis(message.getTimestamp())));
    }

    private RecentMessageEntry requireEntry(Message message) {
        return toEntry(message).orElseThrow(() -> new IllegalArgumentException(
                "Saved message id, roomId and timestamp are required"));
    }

    private Window currentWindow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minus(RECENT_WINDOW);
        return new Window(since, toEpochMillis(since));
    }

    private long toEpochMillis(LocalDateTime timestamp) {
        return timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private record Window(LocalDateTime since, long cutoffMillis) {
    }
}
