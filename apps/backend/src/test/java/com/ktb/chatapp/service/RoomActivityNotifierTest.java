package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomActivityNotifierTest {

    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ScheduledExecutorService scheduler;

    private RoomActivityNotifier notifier() {
        return new RoomActivityNotifier(recentMessageCounter, eventPublisher, scheduler, 1000);
    }

    private Runnable captureScheduledFlush() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                runnableCaptor.capture(),
                eq(1000L),
                eq(TimeUnit.MILLISECONDS));
        return runnableCaptor.getValue();
    }

    @Test
    void notifyMessageStored_recordsImmediatelyAndPublishesAfterDebounce() {
        Message message = savedMessage("message-1");
        when(recentMessageCounter.recordMessage(message)).thenReturn(6);
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(7);

        notifier().notifyMessageStored(message);

        verify(recentMessageCounter).recordMessage(message);
        verifyNoInteractions(eventPublisher);
        captureScheduledFlush().run();

        ArgumentCaptor<RoomActivityEvent> eventCaptor =
                ArgumentCaptor.forClass(RoomActivityEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals("room-1", eventCaptor.getValue().getRoomId());
        assertEquals(7, eventCaptor.getValue().getRecentMessageCount());
    }

    @Test
    void notifyMessageStored_sameRoomRecordsEveryMessageButPublishesOnce() {
        Message first = savedMessage("message-1");
        Message second = savedMessage("message-2");
        Message third = savedMessage("message-3");
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(3);
        RoomActivityNotifier notifier = notifier();

        notifier.notifyMessageStored(first);
        notifier.notifyMessageStored(second);
        notifier.notifyMessageStored(third);
        captureScheduledFlush().run();

        verify(recentMessageCounter).recordMessage(first);
        verify(recentMessageCounter).recordMessage(second);
        verify(recentMessageCounter).recordMessage(third);
        verify(scheduler, times(1))
                .schedule(any(Runnable.class), eq(1000L), eq(TimeUnit.MILLISECONDS));
        verify(recentMessageCounter, times(1)).countRecentMessages("room-1");
        verify(eventPublisher, times(1)).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_nullMessageDoesNothing() {
        notifier().notifyMessageStored(null);

        verifyNoInteractions(recentMessageCounter, eventPublisher);
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void notifyMessageStored_recordFails_stillSchedulesAndPublishes() {
        Message message = savedMessage("message-1");
        when(recentMessageCounter.recordMessage(message))
                .thenThrow(new RuntimeException("redis and mongo down"));
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(7);

        notifier().notifyMessageStored(message);
        captureScheduledFlush().run();

        verify(eventPublisher).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_debouncedCountFails_swallowsException() {
        Message message = savedMessage("message-1");
        when(recentMessageCounter.countRecentMessages("room-1"))
                .thenThrow(new RuntimeException("redis and mongo down"));

        notifier().notifyMessageStored(message);
        captureScheduledFlush().run();

        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void shutdown_stopsScheduler() {
        notifier().shutdown();

        verify(scheduler).shutdown();
    }

    private Message savedMessage(String id) {
        return Message.builder()
                .id(id)
                .roomId("room-1")
                .timestamp(LocalDateTime.now())
                .build();
    }
}
