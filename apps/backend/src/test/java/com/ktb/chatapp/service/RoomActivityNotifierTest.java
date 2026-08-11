package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomActivityNotifierTest {

    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RoomActivityNotifier notifier() {
        return new RoomActivityNotifier(recentMessageCounter, eventPublisher);
    }

    @Test
    void notifyMessageStored_firstMessageOfRoom_publishesRecentMessageCount() {
        Message message = savedMessage();
        when(recentMessageCounter.recordMessage(message)).thenReturn(7);

        notifier().notifyMessageStored(message);

        ArgumentCaptor<RoomActivityEvent> eventCaptor =
                ArgumentCaptor.forClass(RoomActivityEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals("room-1", eventCaptor.getValue().getRoomId());
        assertEquals(7, eventCaptor.getValue().getRecentMessageCount());
    }

    @Test
    void notifyMessageStored_everyMessage_publishes() {
        Message message = savedMessage();
        when(recentMessageCounter.recordMessage(message)).thenReturn(1);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored(message);
        notifier.notifyMessageStored(message);
        notifier.notifyMessageStored(message);

        verify(eventPublisher, times(3)).publishEvent(any(RoomActivityEvent.class));
        verify(recentMessageCounter, times(3)).recordMessage(message);
    }

    @Test
    void notifyMessageStored_nullRoomId_doesNothing() {
        notifier().notifyMessageStored(null);

        verifyNoInteractions(recentMessageCounter);
        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_counterFails_swallowsException() {
        Message message = savedMessage();
        when(recentMessageCounter.recordMessage(message))
                .thenThrow(new RuntimeException("mongo down"));

        notifier().notifyMessageStored(message);

        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }

    private Message savedMessage() {
        return Message.builder()
                .id("message-1")
                .roomId("room-1")
                .timestamp(LocalDateTime.now())
                .build();
    }
}
