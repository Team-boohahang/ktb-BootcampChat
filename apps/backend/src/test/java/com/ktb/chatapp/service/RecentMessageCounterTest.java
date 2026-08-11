package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.service.recentmessage.RecentMessageEntry;
import com.ktb.chatapp.service.recentmessage.RecentMessageMongoStore;
import com.ktb.chatapp.service.recentmessage.RecentMessageRedisStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentMessageCounterTest {

    @Mock private RecentMessageMongoStore mongoStore;
    @Mock private RecentMessageRedisStore redisStore;

    private RecentMessageCounter counter;

    @BeforeEach
    void setUp() {
        counter = new RecentMessageCounter(mongoStore, redisStore);
    }

    @Test
    void countRecentMessages_usesOneRedisPipelineForWarmRooms() {
        Map<String, OptionalInt> redisCounts = new LinkedHashMap<>();
        redisCounts.put("room-1", OptionalInt.of(3));
        redisCounts.put("room-2", OptionalInt.of(5));
        when(redisStore.countAll(any(), anyLong())).thenReturn(redisCounts);

        Map<String, Integer> result = counter.countRecentMessages(List.of("room-1", "room-2"));

        assertEquals(Map.of("room-1", 3, "room-2", 5), result);
        verify(redisStore).countAll(
                argThat(ids -> List.copyOf(ids).equals(List.of("room-1", "room-2"))),
                anyLong());
        verifyNoInteractions(mongoStore);
    }

    @Test
    void countRecentMessages_coldCacheHydratesEveryExistingMessageType() {
        when(redisStore.countAll(any(), anyLong()))
                .thenReturn(Map.of("room-1", OptionalInt.empty()));
        when(mongoStore.streamRecentMessages(any(), any())).thenReturn(List.of(
                message("text-1", MessageType.text),
                message("file-1", MessageType.file),
                message("system-1", MessageType.system),
                message("ai-1", MessageType.ai)).stream());
        when(redisStore.initializeAll(any(), anyLong(), eq(1800L)))
                .thenReturn(Map.of("room-1", 4));

        assertEquals(4, counter.countRecentMessages("room-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ? extends Collection<RecentMessageEntry>>> entries =
                ArgumentCaptor.forClass(Map.class);
        verify(redisStore).initializeAll(entries.capture(), anyLong(), eq(1800L));
        assertEquals(4, entries.getValue().get("room-1").size());
    }

    @Test
    void countRecentMessages_hydratesMoreThanBatchSizeWithoutTruncating() {
        int messageCount = RecentMessageMongoStore.HYDRATION_BATCH_SIZE + 1;
        List<Message> messages = IntStream.range(0, messageCount)
                .mapToObj(index -> message("message-" + index, MessageType.text))
                .toList();
        when(redisStore.countAll(any(), anyLong()))
                .thenReturn(Map.of("room-1", OptionalInt.empty()));
        when(mongoStore.streamRecentMessages(any(), any())).thenReturn(messages.stream());
        when(redisStore.completeInitializationAll(any(), anyLong(), eq(1800L)))
                .thenReturn(Map.of("room-1", messageCount));

        assertEquals(messageCount, counter.countRecentMessages("room-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ? extends Collection<RecentMessageEntry>>> batches =
                ArgumentCaptor.forClass(Map.class);
        verify(redisStore, times(2)).appendAll(batches.capture(), anyLong(), eq(1800L));
        int hydrated = batches.getAllValues().stream()
                .mapToInt(batch -> batch.values().stream().mapToInt(Collection::size).sum())
                .sum();
        assertEquals(messageCount, hydrated);
    }

    @Test
    void countRecentMessages_redisFailureUsesMongoAggregationOnly() {
        when(redisStore.countAll(any(), anyLong())).thenThrow(new RuntimeException("redis down"));
        when(mongoStore.countAll(any(), any())).thenReturn(Map.of("room-1", 7, "room-2", 9));

        Map<String, Integer> result = counter.countRecentMessages(List.of("room-1", "room-2"));

        assertEquals(Map.of("room-1", 7, "room-2", 9), result);
        verify(mongoStore).countAll(
                argThat(ids -> List.copyOf(ids).equals(List.of("room-1", "room-2"))),
                any(LocalDateTime.class));
        verify(mongoStore, never()).streamRecentMessages(any(), any());
    }

    @Test
    void recordMessage_updatesRedisWithoutMongoWhenRoomIsInitialized() {
        Message message = message("message-1", MessageType.text);
        when(redisStore.count(eq("room-1"), anyLong())).thenReturn(OptionalInt.of(2));
        when(redisStore.record(eq("room-1"), eq("message-1"), anyLong(), anyLong(), eq(1800L)))
                .thenReturn(3);

        assertEquals(3, counter.recordMessage(message));

        verifyNoInteractions(mongoStore);
    }

    @Test
    void recordMessage_coldHydrationFiltersInvalidDocuments() {
        Message saved = message("message-1", MessageType.text);
        Message invalid = Message.builder()
                .id("invalid")
                .roomId("room-1")
                .timestamp(null)
                .build();
        when(redisStore.count(eq("room-1"), anyLong())).thenReturn(OptionalInt.empty());
        when(mongoStore.streamRecentMessages(any(), any()))
                .thenReturn(new ArrayList<>(List.of(saved, invalid)).stream());
        when(redisStore.initializeAll(any(), anyLong(), eq(1800L)))
                .thenReturn(Map.of("room-1", 1));
        when(redisStore.record(eq("room-1"), eq("message-1"), anyLong(), anyLong(), eq(1800L)))
                .thenReturn(1);

        assertEquals(1, counter.recordMessage(saved));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ? extends Collection<RecentMessageEntry>>> entries =
                ArgumentCaptor.forClass(Map.class);
        verify(redisStore).initializeAll(entries.capture(), anyLong(), eq(1800L));
        assertEquals(List.of("message-1"), entries.getValue().get("room-1").stream()
                .map(RecentMessageEntry::messageId)
                .toList());
    }

    @Test
    void recordMessage_redisFailureUsesExactMongoAggregationCount() {
        Message message = message("message-1", MessageType.text);
        when(redisStore.count(eq("room-1"), anyLong())).thenThrow(new RuntimeException("redis down"));
        when(mongoStore.countAll(any(), any())).thenReturn(Map.of("room-1", 9));

        assertEquals(9, counter.recordMessage(message));
        verify(mongoStore).countAll(eq(List.of("room-1")), any(LocalDateTime.class));
    }

    private Message message(String id, MessageType type) {
        return Message.builder()
                .id(id)
                .roomId("room-1")
                .type(type)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
