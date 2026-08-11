package com.ktb.chatapp.repository;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "spring.data.mongodb.auto-index-creation=true",
        "socketio.enabled=false"
})
class MessageClientMessageIdIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
    }

    @Test
    @DisplayName("clientMessageId 메시지는 원본 ID를 유지하며 한 번 저장된다")
    void saveMessageWithClientMessageId() {
        String clientMessageId = UUID.randomUUID().toString();

        Message saved = messageRepository.save(message("sender-1", clientMessageId, "hello"));

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getClientMessageId()).isEqualTo(clientMessageId);
        assertThat(messageRepository.countBySenderIdAndClientMessageId("sender-1", clientMessageId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 sender와 clientMessageId는 기존 메시지 한 건으로 수렴한다")
    void duplicateSenderAndClientMessageIdReturnsExistingMessage() {
        String clientMessageId = UUID.randomUUID().toString();
        Message first = saveOrFindExisting(message("sender-1", clientMessageId, "hello"));
        Message duplicate = saveOrFindExisting(message("sender-1", clientMessageId, "hello"));

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(messageRepository.countBySenderIdAndClientMessageId("sender-1", clientMessageId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 sender는 같은 clientMessageId를 각각 저장할 수 있다")
    void sameClientMessageIdCanBeUsedByDifferentSenders() {
        String clientMessageId = UUID.randomUUID().toString();

        Message first = messageRepository.save(message("sender-1", clientMessageId, "hello"));
        Message second = messageRepository.save(message("sender-2", clientMessageId, "hello"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 sender가 서로 다른 clientMessageId를 사용하면 각각 저장된다")
    void sameSenderCanUseDifferentClientMessageIds() {
        Message first = messageRepository.save(message("sender-1", UUID.randomUUID().toString(), "hello"));
        Message second = messageRepository.save(message("sender-1", UUID.randomUUID().toString(), "hello"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("clientMessageId가 없는 기존 메시지는 여러 건 저장할 수 있다")
    void messagesWithoutClientMessageIdAreExcludedFromUniqueIndex() {
        Message first = messageRepository.save(message("sender-1", null, "legacy-1"));
        Message second = messageRepository.save(message("sender-1", null, "legacy-2"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("sender_clientMessageId partial unique index가 생성된다")
    void uniquePartialIndexIsCreated() {
        boolean exists = mongoTemplate.indexOps(Message.class)
                .getIndexInfo()
                .stream()
                .anyMatch(indexInfo -> "sender_clientMessageId_unique_idx".equals(indexInfo.getName()));

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("동일 sender와 clientMessageId 동시 저장은 한 건으로 수렴한다")
    void concurrentDuplicateRequestsConvergeToSingleMessage() throws Exception {
        String clientMessageId = UUID.randomUUID().toString();
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Callable<Message>> tasks = IntStream.range(0, requestCount)
                    .mapToObj(index -> (Callable<Message>) () -> {
                        start.await();
                        return saveOrFindExisting(message("sender-1", clientMessageId, "hello"));
                    })
                    .toList();

            var futures = tasks.stream()
                    .map(executor::submit)
                    .toList();
            start.countDown();

            Set<String> savedIds = futures.stream()
                    .map(future -> {
                        try {
                            return future.get().getId();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertThat(savedIds).hasSize(1);
            assertThat(messageRepository.countBySenderIdAndClientMessageId("sender-1", clientMessageId))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Message saveOrFindExisting(Message message) {
        try {
            return messageRepository.save(message);
        } catch (DuplicateKeyException e) {
            return messageRepository
                    .findBySenderIdAndClientMessageId(message.getSenderId(), message.getClientMessageId())
                    .orElseThrow(() -> e);
        }
    }

    private Message message(String senderId, String clientMessageId, String content) {
        Message message = new Message();
        message.setRoomId("room-1");
        message.setSenderId(senderId);
        message.setClientMessageId(clientMessageId);
        message.setType(MessageType.text);
        message.setContent(content);
        message.setTimestamp(LocalDateTime.now());
        return message;
    }
}
