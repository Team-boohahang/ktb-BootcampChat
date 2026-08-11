package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {
    private static final String USER_ROOM_PREFIX = "user:";
    private static final String SOCKET_SESSION_ROOM_PREFIX = "socket:";
    private static final String ROOM_LIST_ROOM = "room-list";

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;
    private final ScheduledExecutorService duplicateLoginScheduler;

    @Value("${PERF_CHAT_MESSAGE_SLOW_THRESHOLD_MS:${perf.chat-message.slow-threshold-ms:100}}")
    private long perfSlowThresholdMs = 100;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            ScheduledExecutorService duplicateLoginScheduler,
            MeterRegistry meterRegistry) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;
        this.duplicateLoginScheduler = duplicateLoginScheduler;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }
    
    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        long startedAt = System.nanoTime();
        long notifyDuplicateLoginMs = 0;
        long clientSetUserMs = 0;
        long rejoinRoomsMs = 0;
        long connectedUsersSetMs = 0;
        long joinDefaultRoomsMs = 0;
        int rejoinRoomCount = 0;
        String outcome = "exception";
        String userId = user.id();
        long userContextMs = 0;
        
        try {
            // Redis store 모드에서는 socket 전용 room broadcast로 다른 노드의 기존 연결에도 통보한다.
            long stepStartedAt = System.nanoTime();
            notifyDuplicateLogin(client, userId);
            notifyDuplicateLoginMs = elapsedMillis(stepStartedAt);

            stepStartedAt = System.nanoTime();
            client.set("user", user);
            userContextMs = elapsedMillis(stepStartedAt);
            clientSetUserMs = userContextMs;
            
            stepStartedAt = System.nanoTime();
            Set<String> roomsToRejoin = userRooms.get(userId);
            rejoinRoomCount = roomsToRejoin.size();
            roomsToRejoin.forEach(roomId -> {
                // 재접속 시 기존 참여 방 재입장 처리
                roomJoinHandler.handleJoinRoom(client, roomId);
            });
            rejoinRoomsMs = elapsedMillis(stepStartedAt);
            
            stepStartedAt = System.nanoTime();
            connectedUsers.set(userId, user);
            connectedUsersSetMs = elapsedMillis(stepStartedAt);

            log.info("Socket.IO user connected: {} ({}) - Total concurrent users: {}",
                    getUserName(client), userId, connectedUsers.size());

            stepStartedAt = System.nanoTime();
            client.joinRooms(Set.of(USER_ROOM_PREFIX + userId, SOCKET_SESSION_ROOM_PREFIX + client.getSessionId(), ROOM_LIST_ROOM));
            joinDefaultRoomsMs = elapsedMillis(stepStartedAt);
            outcome = "success";
            
        } catch (Exception e) {
            log.error("Error handling Socket.IO connection", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "연결 처리 중 오류가 발생했습니다."
            ));
        } finally {
            long totalMs = elapsedMillis(startedAt);
            if (totalMs >= perfSlowThresholdMs) {
                log.info("[PERF][socketConnect] status={} totalMs={} userContextMs={} duplicateLoginMs={} roomRestoreMs={} restoredRoomCount={} connectedUsersMs={} defaultRoomJoinMs={} userId={} socketId={}",
                        outcome, totalMs, userContextMs, notifyDuplicateLoginMs, rejoinRoomsMs,
                        rejoinRoomCount, connectedUsersSetMs, joinDefaultRoomsMs, userId, client.getSessionId());
            }
        }
    }
    
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        String userName = getUserName(client);
        
        try {
            if (userId == null) {
                return;
            }

            String socketId = client.getSessionId().toString();
            
            // 해당 사용자의 현재 활성 연결인 경우에만 정리
            var socketUser = connectedUsers.get(userId);
            if (socketUser != null && socketId.equals(socketUser.socketId())) {
                Set<String> rooms = new HashSet<>(userRooms.get(userId));
                rooms.forEach(client::leaveRoom);
                userRooms.clear(userId);
                connectedUsers.del(userId);
            } else {
                log.warn("Socket.IO disconnect: User {} has a different active connection. Skipping cleanup.", userId);
            }

            client.leaveRooms(Set.of(USER_ROOM_PREFIX + userId, SOCKET_SESSION_ROOM_PREFIX + client.getSessionId(), ROOM_LIST_ROOM));
            client.del("user");

            log.info("Socket.IO user disconnected: {} ({}) - Total concurrent users: {}",
                    userName, userId, connectedUsers.size());
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
        
    }
    
    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
    
    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) {
            return;
        }
        String existingSocketId = socketUser.socketId();
        String existingSocketRoom = SOCKET_SESSION_ROOM_PREFIX + existingSocketId;

        socketIOServer.getRoomOperations(existingSocketRoom).sendEvent(DUPLICATE_LOGIN, Map.of(
                "type", "new_login_attempt",
                "deviceInfo", client.getHandshakeData().getHttpHeaders().get("User-Agent"),
                "ipAddress", client.getRemoteAddress().toString(),
                "timestamp", System.currentTimeMillis()
        ));
        
        duplicateLoginScheduler.schedule(() -> {
            try {
                socketIOServer.getRoomOperations(existingSocketRoom).sendEvent(SESSION_ENDED, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
                ));
            } catch (Exception e) {
                log.error("Error sending duplicate login session end event", e);
            }
        }, Duration.ofSeconds(10).toSeconds(), TimeUnit.SECONDS);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
