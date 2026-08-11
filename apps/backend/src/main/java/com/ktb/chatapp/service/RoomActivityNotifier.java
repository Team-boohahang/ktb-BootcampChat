package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import com.ktb.chatapp.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;

    public void notifyMessageStored(Message savedMessage) {
        if (savedMessage == null || savedMessage.getRoomId() == null) {
            return;
        }

        try {
            int recentMessageCount = recentMessageCounter.recordMessage(savedMessage);
            eventPublisher.publishEvent(new RoomActivityEvent(
                    this, savedMessage.getRoomId(), recentMessageCount));
        } catch (Exception e) {
            log.error("roomActivity 이벤트 발행 실패: roomId={}", savedMessage.getRoomId(), e);
        }
    }
}
