package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.recentmessage.RecentMessageEntry;
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
import java.util.OptionalInt;
import java.util.Set;
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

    private final MessageRepository messageRepository;
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
                Map<String, List<RecentMessageEntry>> messagesByRoom =
                        loadRecentMessages(missingRoomIds, window.since());
                for (String roomId : missingRoomIds) {
                    int count = redisStore.initialize(
                            roomId,
                            messagesByRoom.getOrDefault(roomId, List.of()),
                            window.cutoffMillis(),
                            RECENT_WINDOW.toSeconds());
                    counts.put(roomId, count);
                }
            }
            return orderedCounts(uniqueRoomIds, counts);
        } catch (RuntimeException redisFailure) {
            log.warn("Redis recent message count failed; falling back to MongoDB", redisFailure);
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
            OptionalInt currentCount = redisStore.count(message.getRoomId(), window.cutoffMillis());
            if (currentCount.isEmpty()) {
                List<RecentMessageEntry> recentMessages = messageRepository
                        .findRecentMessagesByRoomIds(List.of(message.getRoomId()), window.since())
                        .stream()
                        .map(this::toEntry)
                        .toList();
                redisStore.initialize(
                        message.getRoomId(),
                        recentMessages,
                        window.cutoffMillis(),
                        RECENT_WINDOW.toSeconds());
            }
            return redisStore.record(
                    message.getRoomId(),
                    message.getId(),
                    toEpochMillis(message.getTimestamp()),
                    window.cutoffMillis(),
                    RECENT_WINDOW.toSeconds());
        } catch (RuntimeException redisFailure) {
            log.warn("Redis recent message record failed; falling back to MongoDB: roomId={}",
                    message.getRoomId(), redisFailure);
            return Math.toIntExact(messageRepository.countRecentMessagesByRoomId(
                    message.getRoomId(), window.since()));
        }
    }

    private Map<String, List<RecentMessageEntry>> loadRecentMessages(
            Collection<String> roomIds,
            LocalDateTime since) {
        return messageRepository.findRecentMessagesByRoomIds(roomIds, since).stream()
                .filter(message -> message.getId() != null && message.getRoomId() != null
                        && message.getTimestamp() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        Message::getRoomId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(this::toEntry, java.util.stream.Collectors.toList())));
    }

    private Map<String, Integer> mongoCounts(Collection<String> roomIds, LocalDateTime since) {
        Map<String, List<RecentMessageEntry>> messagesByRoom = loadRecentMessages(roomIds, since);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String roomId : roomIds) {
            counts.put(roomId, messagesByRoom.getOrDefault(roomId, List.of()).size());
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

    private RecentMessageEntry toEntry(Message message) {
        return new RecentMessageEntry(message.getId(), toEpochMillis(message.getTimestamp()));
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
