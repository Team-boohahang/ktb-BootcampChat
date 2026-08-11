package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.util.HashMap;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    @Value("${PERF_CHAT_MESSAGE_SLOW_THRESHOLD_MS:${perf.chat-message.slow-threshold-ms:100}}")
    private long perfSlowThresholdMs = 100;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(data.roomId(), data.limit(BATCH_SIZE), data.before(LocalDateTime.now()), userId);
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        long startedAt = System.nanoTime();
        long messagesQueryMs = 0;
        long sortAndIdsMs = 0;
        long readStatusUpdateMs = 0;
        long senderQueryMs = 0;
        long mappingMs = 0;
        long hasMoreMs = 0;
        int messageCount = 0;
        int senderCount = 0;
        long fileMessageCount = 0;

        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        long stepStartedAt = System.nanoTime();
        Page<Message> messagePage = messageRepository
                .findByRoomIdAndTimestampBefore(roomId, before, pageable);
        messagesQueryMs = elapsedMillis(stepStartedAt);

        List<Message> messages = messagePage.getContent();
        messageCount = messages.size();

        stepStartedAt = System.nanoTime();
        // DESC로 조회했으므로 ASC로 재정렬 (채팅 UI 표시 순서)
        List<Message> sortedMessages = messages.reversed();
        
        var messageIds = sortedMessages.stream().map(Message::getId).toList();
        senderCount = (int) sortedMessages.stream()
                  .map(Message::getSenderId)
                  .filter(id -> id != null && !id.isBlank())
                  .distinct()
                  .count();

        fileMessageCount = sortedMessages.stream()
                  .filter(message -> message.getFileId() != null && !message.getFileId().isBlank())
                  .count();

        sortAndIdsMs = elapsedMillis(stepStartedAt);

        stepStartedAt = System.nanoTime();

        boolean readStatusUpdated =
                messageReadStatusService.updateReadStatus(
                        roomId,
                        messageIds,
                        userId
                );

        readStatusUpdateMs = elapsedMillis(stepStartedAt);

        if (!readStatusUpdated) {
            log.warn(
                "Failed to update read status while loading messages for room {}",
                roomId
            );
        }
               

        stepStartedAt = System.nanoTime();
        Map<String, User> usersById = findUsersById(sortedMessages);
        senderQueryMs = elapsedMillis(stepStartedAt);
        
        // 메시지 응답 생성
        stepStartedAt = System.nanoTime();
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> messageResponseMapper.mapToMessageResponse(
                        message,
                        usersById.get(message.getSenderId())))
                .collect(Collectors.toList());
        mappingMs = elapsedMillis(stepStartedAt);

        stepStartedAt = System.nanoTime();
        boolean hasMore = messagePage.hasNext();
        hasMoreMs = elapsedMillis(stepStartedAt);

        long totalMs = elapsedMillis(startedAt);
        if (totalMs >= perfSlowThresholdMs) {
            log.info("[PERF][messageLoader] status=success totalMs={} messagesQueryMs={} sortAndIdsMs={} readStatusUpdateMs={} senderQueryMs={} mappingMs={} hasMoreMs={} messageCount={} senderCount={} fileMessageCount={} roomId={} userId={}",
                    totalMs, messagesQueryMs, sortAndIdsMs, readStatusUpdateMs, senderQueryMs,
                    mappingMs, hasMoreMs, messageCount, senderCount, fileMessageCount, roomId, userId);
        }

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }

    /**
     * AI 경우 null 반환 가능
     */
    private Map<String, User> findUsersById(List<Message> messages) {
        List<String> senderIds = messages.stream()
                .map(Message::getSenderId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        Map<String, User> usersById = new HashMap<>();
        if (senderIds.isEmpty()) {
            return usersById;
        }

        userRepository.findAllById(senderIds)
                .forEach(user -> usersById.put(user.getId(), user));
        return usersById;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
