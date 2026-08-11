package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.recentmessage.RecentMessageEntry;
import com.ktb.chatapp.service.recentmessage.RecentMessageRedisStore;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentMessageCounterTest {

    @Mock private MessageRepository messageRepository;
    @Mock private RecentMessageRedisStore redisStore;

    private RecentMessageCounter counter;

    @BeforeEach
    void setUp() {
        counter = new RecentMessageCounter(messageRepository, redisStore);
    }

    @Test
    void countRecentMessages_usesOneRedisPipelineForAllRooms() {
        Map<String, OptionalInt> redisCounts = new LinkedHashMap<>();
        redisCounts.put("room-1", OptionalInt.of(3));
        redisCounts.put("room-2", OptionalInt.of(5));
        when(redisStore.countAll(any(), anyLong())).thenReturn(redisCounts);

        Map<String, Integer> result = counter.countRecentMessages(List.of("room-1", "room-2"));

        assertEquals(Map.of("room-1", 3, "room-2", 5), result);
        verify(redisStore).countAll(
                argThat(ids -> List.copyOf(ids).equals(List.of("room-1", "room-2"))),
                anyLong());
        verify(messageRepository, never()).findRecentMessagesByRoomIds(any(), any());
    }

    @Test
    void countRecentMessages_coldCacheHydratesEveryExistingMessageType() {
        Map<String, OptionalInt> missing = Map.of("room-1", OptionalInt.empty());
        when(redisStore.countAll(any(), anyLong())).thenReturn(missing);
        when(messageRepository.findRecentMessagesByRoomIds(any(), any())).thenReturn(List.of(
                message("text-1", MessageType.text),
                message("file-1", MessageType.file),
                message("system-1", MessageType.system),
                message("ai-1", MessageType.ai)));
        when(redisStore.initialize(eq("room-1"), any(), anyLong(), eq(1800L))).thenReturn(4);

        assertEquals(4, counter.countRecentMessages("room-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<RecentMessageEntry>> entries =
                ArgumentCaptor.forClass(Collection.class);
        verify(redisStore).initialize(eq("room-1"), entries.capture(), anyLong(), eq(1800L));
        assertEquals(4, entries.getValue().size());
    }

    @Test
    void countRecentMessages_redisFailureFallsBackToOneMongoQuery() {
        when(redisStore.countAll(any(), anyLong())).thenThrow(new RuntimeException("redis down"));
        when(messageRepository.findRecentMessagesByRoomIds(any(), any())).thenReturn(List.of(
                message("message-1", MessageType.text),
                Message.builder()
                        .id("message-2")
                        .roomId("room-2")
                        .type(MessageType.ai)
                        .timestamp(LocalDateTime.now())
                        .build()));

        Map<String, Integer> result = counter.countRecentMessages(List.of("room-1", "room-2"));

        assertEquals(Map.of("room-1", 1, "room-2", 1), result);
        verify(messageRepository).findRecentMessagesByRoomIds(
                argThat(ids -> List.copyOf(ids).equals(List.of("room-1", "room-2"))),
                any(LocalDateTime.class));
    }

    @Test
    void recordMessage_updatesRedisWithoutMongoWhenRoomIsInitialized() {
        Message message = message("message-1", MessageType.text);
        when(redisStore.count(eq("room-1"), anyLong())).thenReturn(OptionalInt.of(2));
        when(redisStore.record(eq("room-1"), eq("message-1"), anyLong(), anyLong(), eq(1800L)))
                .thenReturn(3);

        assertEquals(3, counter.recordMessage(message));

        verify(messageRepository, never()).findRecentMessagesByRoomIds(any(), any());
    }

    @Test
    void recordMessage_redisFailureUsesExactMongoCount() {
        Message message = message("message-1", MessageType.text);
        when(redisStore.count(eq("room-1"), anyLong())).thenThrow(new RuntimeException("redis down"));
        when(messageRepository.countRecentMessagesByRoomId(eq("room-1"), any())).thenReturn(9L);

        assertEquals(9, counter.recordMessage(message));
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
