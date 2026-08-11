package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE_REACTION_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReactionHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageRepository messageRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReactionHandler handler;
    private ScheduledExecutorService reactionBroadcastScheduler;

    @BeforeEach
    void setUp() {
        reactionBroadcastScheduler = Executors.newSingleThreadScheduledExecutor();
        handler = new MessageReactionHandler(socketIOServer, messageRepository, mongoTemplate, reactionBroadcastScheduler);
    }

    @AfterEach
    void tearDown() {
        reactionBroadcastScheduler.shutdownNow();
    }

    @Test
    void handleMessageReaction_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleMessageReaction(client, new MessageReactionRequest("👍", "message-1", "add", "👍"));

        verify(client).sendEvent(eq(ERROR), any());
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(Message.class));
    }

    @Test
    void handleMessageReaction_addsReactionAndBroadcasts() {
        Message message = Message.builder()
                .id("message-1")
                .roomId("room-1")
                .reactions(Map.of("👍", Set.of("user-1")))
                .build();
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(message);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMessageReaction(client, request);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(
                queryCaptor.capture(),
                updateCaptor.capture(),
                any(FindAndModifyOptions.class),
                eq(Message.class));
        assertEquals("message-1", queryCaptor.getValue().getQueryObject().get("_id"));
        Document addToSet = updateCaptor.getValue().getUpdateObject().get("$addToSet", Document.class);
        assertEquals("user-1", addToSet.get("reactions.👍"));
        verify(messageRepository, never()).save(any());

        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations, timeout(500)).sendEvent(eq(MESSAGE_REACTION_UPDATE), responseCaptor.capture());
        MessageReactionResponse response = (MessageReactionResponse) responseCaptor.getValue();
        assertEquals("message-1", response.getMessageId());
        assertEquals(Set.of("user-1"), response.getReactions().get("👍"));
    }

    @Test
    void handleMessageReaction_skipsSaveAndBroadcastWhenReactionDoesNotChange() {
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(null);
        when(messageRepository.existsById("message-1")).thenReturn(true);

        handler.handleMessageReaction(client, request);

        verify(messageRepository, never()).save(any());
        verify(socketIOServer, never()).getRoomOperations(any());
    }

    @Test
    void handleMessageReaction_coalescesMultipleUpdatesForSameMessage() {
        Message first = Message.builder()
                .id("message-1")
                .roomId("room-1")
                .reactions(Map.of("👍", Set.of("user-1")))
                .build();
        Message second = Message.builder()
                .id("message-1")
                .roomId("room-1")
                .reactions(Map.of("👍", Set.of("user-1", "user-2")))
                .build();
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(first, second);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"))
                .thenReturn(new SocketUser("user-2", "tester2", "session-2", "socket-2"));

        handler.handleMessageReaction(client, request);
        handler.handleMessageReaction(client, request);

        verify(messageRepository, never()).save(any());
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations, timeout(500).times(1))
                .sendEvent(eq(MESSAGE_REACTION_UPDATE), responseCaptor.capture());
        MessageReactionResponse response = (MessageReactionResponse) responseCaptor.getValue();
        assertEquals(Set.of("user-1", "user-2"), response.getReactions().get("👍"));
    }

    @Test
    void handleMessageReaction_debouncesTenUpdatesForSameMessageIntoOneBroadcast() {
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenAnswer(invocation -> {
                    int applied = (int) handler.reactionChangesApplied() + 1;
                    Set<String> users = new LinkedHashSet<>();
                    for (int i = 1; i <= applied; i++) {
                        users.add("user-" + i);
                    }
                    return Message.builder()
                            .id("message-1")
                            .roomId("room-1")
                            .reactions(Map.of("👍", users))
                            .build();
                });
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        for (int i = 0; i < 10; i++) {
            handler.handleMessageReaction(client, request);
        }

        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations, timeout(800).times(1))
                .sendEvent(eq(MESSAGE_REACTION_UPDATE), responseCaptor.capture());
        MessageReactionResponse response = (MessageReactionResponse) responseCaptor.getValue();
        assertEquals(10, response.getReactions().get("👍").size());
        assertEquals(10, handler.reactionChangesApplied());
        assertEquals(1, handler.reactionBroadcastEmits());
        assertEquals(9, handler.coalescedReactionUpdates());
        verify(mongoTemplate, times(10)).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class));
    }

    @Test
    void handleMessageReaction_coalescesDifferentMessagesIndependently() {
        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(
                        message("message-1", "room-1", Set.of("user-1")),
                        message("message-2", "room-1", Set.of("user-1")),
                        message("message-3", "room-1", Set.of("user-1")));
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMessageReaction(client, new MessageReactionRequest("👍", "message-1", "add", "👍"));
        handler.handleMessageReaction(client, new MessageReactionRequest("👍", "message-2", "add", "👍"));
        handler.handleMessageReaction(client, new MessageReactionRequest("👍", "message-3", "add", "👍"));

        verify(roomOperations, timeout(800).times(3))
                .sendEvent(eq(MESSAGE_REACTION_UPDATE), any());
        assertEquals(3, handler.reactionChangesApplied());
        assertEquals(3, handler.reactionBroadcastEmits());
        assertEquals(0, handler.coalescedReactionUpdates());
    }

    @Test
    void handleMessageReaction_broadcastsAgainForSameMessageAfterCoalesceWindow() throws Exception {
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(
                        message("message-1", "room-1", Set.of("user-1")),
                        message("message-1", "room-1", Set.of("user-1", "user-2")));
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMessageReaction(client, request);
        verify(roomOperations, timeout(800).times(1))
                .sendEvent(eq(MESSAGE_REACTION_UPDATE), any());

        Thread.sleep(150);
        handler.handleMessageReaction(client, request);

        verify(roomOperations, timeout(800).times(2))
                .sendEvent(eq(MESSAGE_REACTION_UPDATE), any());
        assertEquals(2, handler.reactionChangesApplied());
        assertEquals(2, handler.reactionBroadcastEmits());
        assertEquals(0, handler.coalescedReactionUpdates());
    }

    @Test
    void handleMessageReaction_removesReactionWithAtomicPull() {
        Message message = Message.builder()
                .id("message-1")
                .roomId("room-1")
                .reactions(new HashMap<>())
                .build();
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "remove", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(message);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMessageReaction(client, request);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(
                any(Query.class),
                updateCaptor.capture(),
                any(FindAndModifyOptions.class),
                eq(Message.class));
        Document pull = updateCaptor.getValue().getUpdateObject().get("$pull", Document.class);
        assertEquals("user-1", pull.get("reactions.👍"));
        verify(roomOperations, timeout(500)).sendEvent(eq(MESSAGE_REACTION_UPDATE), any());
    }

    @Test
    void handleMessageReaction_sendsErrorWhenMessageDoesNotExist() {
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Message.class))).thenReturn(null);
        when(messageRepository.existsById("message-1")).thenReturn(false);

        handler.handleMessageReaction(client, request);

        verify(client).sendEvent(eq(ERROR), any());
        verify(socketIOServer, never()).getRoomOperations(any());
    }

    private Message message(String messageId, String roomId, Set<String> users) {
        return Message.builder()
                .id(messageId)
                .roomId(roomId)
                .reactions(Map.of("👍", users))
                .build();
    }
}
