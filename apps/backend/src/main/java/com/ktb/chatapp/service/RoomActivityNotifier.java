package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService scheduler;
    private final long debounceMillis;
    private final Map<String, Boolean> pendingRooms = new ConcurrentHashMap<>();

    @Autowired
    public RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher,
            @Value("${room.activity.debounce-ms:1000}") long debounceMillis) {
        this(
                recentMessageCounter,
                eventPublisher,
                createScheduler(),
                debounceMillis);
    }

    RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher,
            ScheduledExecutorService scheduler,
            long debounceMillis) {
        this.recentMessageCounter = recentMessageCounter;
        this.eventPublisher = eventPublisher;
        this.scheduler = scheduler;
        this.debounceMillis = debounceMillis;
    }

    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        if (pendingRooms.putIfAbsent(roomId, Boolean.TRUE) != null) {
            return;
        }

        scheduler.schedule(() -> publishRoomActivity(roomId), debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void publishRoomActivity(String roomId) {
        pendingRooms.remove(roomId);

        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", roomId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    private static ScheduledExecutorService createScheduler() {
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "room-activity-notifier-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
