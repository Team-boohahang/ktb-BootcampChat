package com.ktb.chatapp.service.recentmessage;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = "socketio.enabled=false")
class RecentMessageMongoStoreIntegrationTest {

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private RecentMessageMongoStore store;

    @BeforeEach
    void clearMessages() {
        mongoTemplate.dropCollection(Message.class);
    }

    @Test
    void countAll_returnsExactCountsWithoutLoadingMessageDocuments() {
        LocalDateTime now = LocalDateTime.now();
        insertPreservingTimestamps(List.of(
                message("room-1", MessageType.text, now.minusMinutes(1)),
                message("room-1", MessageType.file, now.minusMinutes(2)),
                message("room-1", MessageType.system, now.minusMinutes(3)),
                message("room-1", MessageType.ai, now.minusMinutes(4)),
                message("room-2", MessageType.text, now.minusMinutes(5)),
                message("room-2", MessageType.text, now.minusMinutes(31))));

        Map<String, Integer> counts =
                store.countAll(List.of("room-1", "room-2", "empty-room"), now.minusMinutes(30));

        assertEquals(Map.of("room-1", 4, "room-2", 1), counts);
    }

    @Test
    void streamRecentMessages_filtersWindowAndProjectsHydrationFields() {
        LocalDateTime now = LocalDateTime.now();
        insertPreservingTimestamps(List.of(
                message("room-1", MessageType.text, now.minusMinutes(1)),
                message("room-2", MessageType.ai, now.minusMinutes(2)),
                message("room-1", MessageType.file, now.minusMinutes(31))));

        List<Message> messages;
        try (var stream = store.streamRecentMessages(List.of("room-1", "room-2"), now.minusMinutes(30))) {
            messages = stream.toList();
        }

        assertEquals(2, messages.size());
        assertEquals(List.of("room-1", "room-2"), messages.stream()
                .map(Message::getRoomId)
                .sorted()
                .toList());
    }

    private Message message(String roomId, MessageType type, LocalDateTime timestamp) {
        return Message.builder()
                .roomId(roomId)
                .content(type.name())
                .type(type)
                .timestamp(timestamp)
                .build();
    }

    private void insertPreservingTimestamps(List<Message> messages) {
        for (Message message : messages) {
            LocalDateTime timestamp = message.getTimestamp();
            Message inserted = mongoTemplate.insert(message);
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(inserted.getId())),
                    Update.update("timestamp", timestamp),
                    Message.class);
        }
    }
}
