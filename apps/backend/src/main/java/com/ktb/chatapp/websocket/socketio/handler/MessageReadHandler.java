package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러
 * 메시지 읽음 상태 업데이트 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {
    private static final String USER_ROOM_PREFIX = "user:";
    
    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    
    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()) {
                return;
            }
            
            String roomId = messageRepository.findById(data.getMessageIds().getFirst())
                    .map(Message::getRoomId).orElse(null);
            
            if (roomId == null || roomId.isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(ERROR, Map.of("message", "User not found"));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }
            
            long modifiedCount = messageReadStatusService
                    .updateReadStatus(roomId, data.getMessageIds(), userId);
            if (modifiedCount < 0) {
                client.sendEvent(ERROR, Map.of(
                        "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
                ));
                return;
            }
            if (modifiedCount == 0) {
                return;
            }

            Map<String, List<String>> senderToMessageIds = groupMessageIdsBySender(data.getMessageIds(), userId);
            senderToMessageIds.forEach((senderId, messageIds) -> {
                MessagesReadResponse response = new MessagesReadResponse(userId, messageIds);
                socketIOServer.getRoomOperations(USER_ROOM_PREFIX + senderId)
                        .sendEvent(MESSAGES_READ, response);
            });

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }

    private Map<String, List<String>> groupMessageIdsBySender(List<String> requestedMessageIds, String readerUserId) {
        Map<String, Message> messageById = new LinkedHashMap<>();
        messageRepository.findAllById(requestedMessageIds).forEach(message -> {
            if (message.getId() != null) {
                messageById.put(message.getId(), message);
            }
        });

        return requestedMessageIds.stream()
                .distinct()
                .map(messageById::get)
                .filter(message -> message != null
                        && message.getSenderId() != null
                        && !message.getSenderId().equals(readerUserId))
                .collect(Collectors.groupingBy(
                        Message::getSenderId,
                        LinkedHashMap::new,
                        Collectors.mapping(Message::getId, Collectors.toCollection(ArrayList::new))));
    }
}
