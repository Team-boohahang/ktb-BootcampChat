package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private ScheduledExecutorService duplicateLoginScheduler;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations broadcastOperations;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                duplicateLoginScheduler,
                new SimpleMeterRegistry());
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(client.getSessionId()).thenReturn(socketId);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "socket:" + socketId, "room-list"));
        verifyNoInteractions(duplicateLoginScheduler);
    }

    @Test
    void onConnect_schedulesSessionEndedForDuplicateLogin() {
        UUID newSocketId = UUID.randomUUID();
        SocketUser existingUser = new SocketUser("user-1", "tester", "session-1", "existing-socket");
        SocketUser newUser = new SocketUser("user-1", "tester", "session-2", newSocketId.toString());
        when(connectedUsers.get(newUser.id())).thenReturn(existingUser);
        when(client.get("user")).thenReturn(newUser);
        when(client.getSessionId()).thenReturn(newSocketId);
        when(client.getHandshakeData()).thenReturn(new HandshakeData(
                new DefaultHttpHeaders().add("User-Agent", "JUnit"),
                java.util.Collections.emptyMap(),
                new InetSocketAddress("127.0.0.1", 12345),
                "/socket.io",
                false));
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        when(userRooms.get(newUser.id())).thenReturn(Set.of());
        when(socketIOServer.getRoomOperations("socket:existing-socket")).thenReturn(broadcastOperations);

        handler.onConnect(client, newUser);

        verify(duplicateLoginScheduler).schedule(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
    }

    @Test
    void onDisconnect_removesCurrentConnectionAndClearsRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(client).leaveRoom("room-1");
        verify(userRooms).clear(user.id());
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "socket:" + socketId, "room-list"));
        verify(client).del("user");
    }
}
