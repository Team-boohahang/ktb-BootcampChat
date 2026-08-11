package com.ktb.chatapp.service;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MessageReadStatusServiceIntegrationTest {

    @Autowired private MessageReadStatusService service;
    @Autowired private MessageRepository messageRepository;
    @Autowired private MongoTemplate mongoTemplate;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
    }

    @Test
    void updateReadStatus_updatesOnlyUnreadMessagesInTheAuthorizedRoom() {
        LocalDateTime existingReadAt = LocalDateTime.now().minusMinutes(1);
        Message unread = saveMessage("unread", "room-1", new ArrayList<>());
        Message alreadyRead = saveMessage("already-read", "room-1", new ArrayList<>(List.of(
                Message.MessageReader.builder()
                        .userId("user-1")
                        .readAt(existingReadAt)
                        .build())));
        Message nullReaders = saveMessage("null-readers", "room-1", new ArrayList<>());
        Message otherRoom = saveMessage("other-room", "room-2", new ArrayList<>());
        LocalDateTime persistedExistingReadAt = find(alreadyRead.getId())
                .getReaders().getFirst().getReadAt();
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(nullReaders.getId())),
                Update.update("readers", null),
                Message.class);

        List<String> requestedIds = List.of(
                unread.getId(),
                unread.getId(),
                alreadyRead.getId(),
                nullReaders.getId(),
                otherRoom.getId());

        assertThat(service.updateReadStatus("room-1", requestedIds, "user-1")).isTrue();
        assertThat(service.updateReadStatus("room-1", requestedIds, "user-1")).isTrue();

        Message updatedUnread = find(unread.getId());
        Message updatedAlreadyRead = find(alreadyRead.getId());
        Message updatedNullReaders = find(nullReaders.getId());
        Message unchangedOtherRoom = find(otherRoom.getId());

        assertThat(updatedUnread.getReaders())
                .extracting(Message.MessageReader::getUserId)
                .containsExactly("user-1");
        assertThat(updatedAlreadyRead.getReaders()).hasSize(1);
        assertThat(updatedAlreadyRead.getReaders().getFirst().getReadAt())
                .isEqualTo(persistedExistingReadAt);
        assertThat(updatedNullReaders.getReaders())
                .extracting(Message.MessageReader::getUserId)
                .containsExactly("user-1");
        assertThat(unchangedOtherRoom.getReaders()).isEmpty();
        assertThat(updatedUnread.getContent()).isEqualTo("content-unread");
        assertThat(updatedUnread.getReactions()).containsEntry("like", Set.of("reactor"));
        assertThat(updatedUnread.getMetadata()).containsEntry("source", "test");
    }

    @Test
    void updateReadStatus_processesMoreThanOneBatchWithoutTruncating() {
        List<Message> messages = new ArrayList<>();
        for (int index = 0; index < MessageReadStatusService.UPDATE_BATCH_SIZE + 1; index++) {
            messages.add(Message.builder()
                    .roomId("room-1")
                    .content("message-" + index)
                    .senderId("sender")
                    .type(MessageType.text)
                    .timestamp(LocalDateTime.now())
                    .readers(new ArrayList<>())
                    .build());
        }
        List<Message> saved = messageRepository.saveAll(messages);

        assertThat(service.updateReadStatus(
                "room-1",
                saved.stream().map(Message::getId).toList(),
                "user-1")).isTrue();

        long readCount = messageRepository.findAllById(
                        saved.stream().map(Message::getId).toList())
                .stream()
                .filter(message -> message.getReaders().stream()
                        .anyMatch(reader -> "user-1".equals(reader.getUserId())))
                .count();
        assertThat(readCount).isEqualTo(saved.size());
    }

    private Message saveMessage(
            String idSuffix,
            String roomId,
            List<Message.MessageReader> readers) {
        return messageRepository.save(Message.builder()
                .roomId(roomId)
                .content("content-" + idSuffix)
                .senderId("sender")
                .type(MessageType.text)
                .timestamp(LocalDateTime.now())
                .readers(readers)
                .reactions(new HashMap<>(Map.of("like", Set.of("reactor"))))
                .metadata(new HashMap<>(Map.of("source", "test")))
                .build());
    }

    private Message find(String messageId) {
        return messageRepository.findById(messageId).orElseThrow();
    }
}
