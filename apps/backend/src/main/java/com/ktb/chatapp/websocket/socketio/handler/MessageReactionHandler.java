package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 리액션 처리 핸들러
 * 메시지 이모지 리액션 추가/제거 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class MessageReactionHandler {
    
    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;
    private final ScheduledExecutorService reactionBroadcastScheduler;
    private final Map<String, PendingReactionBatch> pendingReactionBatches = new ConcurrentHashMap<>();
    private final AtomicLong reactionChangesApplied = new AtomicLong();
    private final AtomicLong reactionBroadcastEmits = new AtomicLong();
    private final AtomicLong reactionRecipients = new AtomicLong();
    private final AtomicLong reactionBatchMessages = new AtomicLong();
    private final AtomicLong coalescedReactionUpdates = new AtomicLong();
    private final AtomicLong reactionBatchSizeMax = new AtomicLong();

    @Value("${PERF_CHAT_MESSAGE_SLOW_THRESHOLD_MS:${perf.chat-message.slow-threshold-ms:100}}")
    private long perfSlowThresholdMs = 100;

    @Value("${REACTION_BROADCAST_COALESCE_MS:${reaction.broadcast.coalesce-ms:100}}")
    private long reactionBroadcastCoalesceMs = 100;

    @Value("${REACTION_BROADCAST_BATCH_SIZE:${reaction.broadcast.batch-size:100}}")
    private int reactionBroadcastBatchSize = 100;

    @Autowired
    public MessageReactionHandler(
            SocketIOServer socketIOServer,
            MessageRepository messageRepository,
            MongoTemplate mongoTemplate) {
        this(socketIOServer, messageRepository, mongoTemplate, createReactionBroadcastScheduler());
    }

    MessageReactionHandler(
            SocketIOServer socketIOServer,
            MessageRepository messageRepository,
            MongoTemplate mongoTemplate,
            ScheduledExecutorService reactionBroadcastScheduler) {
        this.socketIOServer = socketIOServer;
        this.messageRepository = messageRepository;
        this.mongoTemplate = mongoTemplate;
        this.reactionBroadcastScheduler = reactionBroadcastScheduler;
        this.reactionBroadcastScheduler.scheduleWithFixedDelay(
                this::logReactionMetrics,
                10,
                10,
                TimeUnit.SECONDS);
    }
    
    @OnEvent(MESSAGE_REACTION)
    public void handleMessageReaction(SocketIOClient client, MessageReactionRequest data) {
        long startedAt = System.nanoTime();
        long userContextMs = 0;
        long validationMs = 0;
        long reactionUpdateMs = 0;
        long reactionMutationMs = 0;
        long broadcastMs = 0;
        boolean changed = false;
        String status = "exception";
        String roomId = null;
        String messageId = data != null ? data.getMessageId() : null;
        try {
            long stepStartedAt = System.nanoTime();
            String userId = getUserId(client);
            userContextMs = elapsedMillis(stepStartedAt);
            if (userId == null || userId.isBlank()) {
                status = "unauthorized";
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            stepStartedAt = System.nanoTime();
            ReactionUpdateCommand command = buildReactionUpdateCommand(data, userId);
            validationMs = elapsedMillis(stepStartedAt);
            if (command == null) {
                status = "invalid_type";
                client.sendEvent(ERROR, Map.of("message", "지원하지 않는 리액션 타입입니다."));
                return;
            }

            stepStartedAt = System.nanoTime();
            Message message = mongoTemplate.findAndModify(
                    command.query(),
                    command.update(),
                    FindAndModifyOptions.options().returnNew(true),
                    Message.class);
            reactionUpdateMs = elapsedMillis(stepStartedAt);
            reactionMutationMs = reactionUpdateMs;
            changed = message != null;

            if (!changed) {
                if (!messageRepository.existsById(data.getMessageId())) {
                    status = "message_not_found";
                    client.sendEvent(ERROR, Map.of("message", "메시지를 찾을 수 없습니다."));
                    return;
                }
                status = "no_change";
                return;
            }
            roomId = message.getRoomId();
            reactionChangesApplied.incrementAndGet();

            stepStartedAt = System.nanoTime();
            enqueueReactionUpdate(message.getId(), message.getRoomId(), compactReactions(message.getReactions()));
            broadcastMs = elapsedMillis(stepStartedAt);
            status = "success";

        } catch (Exception e) {
            log.error("Error handling messageReaction", e);
            client.sendEvent(ERROR, Map.of(
                "message", "리액션 처리 중 오류가 발생했습니다."
            ));
        } finally {
            long totalMs = elapsedMillis(startedAt);
            if (totalMs >= perfSlowThresholdMs) {
                log.info("[PERF][reaction] status={} totalMs={} userContextMs={} sessionMs={} rateLimitMs={} validationMs={} messageQueryMs={} reactionUpdateMs={} reactionMutationMs={} messageSaveMs={} broadcastMs={} changed={} roomId={} messageId={}",
                        status, totalMs, userContextMs, 0, 0, validationMs, 0, reactionUpdateMs,
                        reactionMutationMs, 0, broadcastMs, changed, roomId, messageId);
            }
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }

    private void enqueueReactionUpdate(String messageId, String roomId, Map<String, Set<String>> reactions) {
        pendingReactionBatches.compute(roomId, (key, existing) -> {
            if (existing == null) {
                PendingReactionBatch batch = new PendingReactionBatch();
                batch.update(messageId, reactions);
                batch.reschedule(scheduleReactionFlush(roomId, batch.version(), batch.delayMs(reactionBroadcastCoalesceMs, reactionBroadcastBatchSize)));
                return batch;
            }

            existing.update(messageId, reactions);
            existing.reschedule(scheduleReactionFlush(roomId, existing.version(), existing.delayMs(reactionBroadcastCoalesceMs, reactionBroadcastBatchSize)));
            return existing;
        });
    }

    private ScheduledFuture<?> scheduleReactionFlush(String roomId, long version, long delayMs) {
        return reactionBroadcastScheduler.schedule(
                () -> flushReactionBatch(roomId, version),
                delayMs,
                TimeUnit.MILLISECONDS);
    }

    private void flushReactionBatch(String roomId, long expectedVersion) {
        AtomicReference<PendingReactionBatch> batchToFlush = new AtomicReference<>();
        pendingReactionBatches.computeIfPresent(roomId, (key, batch) -> {
            if (batch.version() != expectedVersion) {
                return batch;
            }
            batchToFlush.set(batch);
            return null;
        });

        PendingReactionBatch batch = batchToFlush.get();
        if (batch == null) {
            return;
        }

        long startedAt = System.nanoTime();
        List<MessageReactionResponse> responses = batch.responses();
        var roomOperations = socketIOServer.getRoomOperations(roomId);
        int recipients = roomOperations.getClients().size();
        roomOperations.sendEvent(MESSAGE_REACTION_UPDATES, responses);
        reactionBroadcastEmits.incrementAndGet();
        reactionRecipients.addAndGet(recipients);
        reactionBatchMessages.addAndGet(responses.size());
        updateMax(reactionBatchSizeMax, responses.size());
        coalescedReactionUpdates.addAndGet(Math.max(0, batch.changeCount() - responses.size()));
        long broadcastMs = elapsedMillis(startedAt);
        if (broadcastMs >= perfSlowThresholdMs) {
            log.info("[PERF][reaction] status={} totalMs={} userContextMs={} sessionMs={} rateLimitMs={} validationMs={} messageQueryMs={} reactionUpdateMs={} reactionMutationMs={} messageSaveMs={} broadcastMs={} changed={} roomId={} batchSize={} recipients={}",
                    "broadcast_flush", broadcastMs, 0, 0, 0, 0, 0, 0, 0, 0, broadcastMs, true, roomId, responses.size(), recipients);
        }
    }

    private ReactionUpdateCommand buildReactionUpdateCommand(MessageReactionRequest data, String userId) {
        String reactionField = "reactions." + data.getReaction();
        return switch (data.getType()) {
            case "add" -> new ReactionUpdateCommand(
                    new Query(Criteria.where("_id").is(data.getMessageId()).and(reactionField).ne(userId)),
                    new Update().addToSet(reactionField, userId));
            case "remove" -> new ReactionUpdateCommand(
                    new Query(Criteria.where("_id").is(data.getMessageId()).and(reactionField).is(userId)),
                    new Update().pull(reactionField, userId));
            case null, default -> null;
        };
    }

    private Map<String, Set<String>> compactReactions(Map<String, Set<String>> reactions) {
        if (reactions == null || reactions.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> compacted = new HashMap<>();
        reactions.forEach((emoji, users) -> {
            if (users != null && !users.isEmpty()) {
                compacted.put(emoji, users);
            }
        });
        return compacted;
    }

    @PreDestroy
    void shutdownReactionBroadcastScheduler() {
        flushPendingReactionBatches();
        logReactionMetrics();
        reactionBroadcastScheduler.shutdownNow();
    }

    private void logReactionMetrics() {
        long emits = reactionBroadcastEmits.get();
        long batchMessages = reactionBatchMessages.get();
        long batchSizeAvg = emits > 0 ? batchMessages / emits : 0;
        long recipientAvg = emits > 0 ? reactionRecipients.get() / emits : 0;
        log.info("[METRIC][reaction] reactionChangesApplied={} reactionBroadcastEmits={} reactionRecipients={} reactionRecipientsAvg={} reactionBatchMessages={} reactionBatchSizeAvg={} reactionBatchSizeMax={} coalescedReactionUpdates={} pendingReactionBatches={}",
                reactionChangesApplied.get(), emits, reactionRecipients.get(), recipientAvg,
                batchMessages, batchSizeAvg, reactionBatchSizeMax.get(),
                coalescedReactionUpdates.get(), pendingReactionBatches.size());
    }

    private void flushPendingReactionBatches() {
        List<String> roomIds = new ArrayList<>(pendingReactionBatches.keySet());
        for (String roomId : roomIds) {
            PendingReactionBatch batch = pendingReactionBatches.get(roomId);
            if (batch != null) {
                flushReactionBatch(roomId, batch.version());
            }
        }
    }

    private static ScheduledExecutorService createReactionBroadcastScheduler() {
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "socketio-reaction-broadcast-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    long reactionChangesApplied() {
        return reactionChangesApplied.get();
    }

    long reactionBroadcastEmits() {
        return reactionBroadcastEmits.get();
    }

    long reactionBatchMessages() {
        return reactionBatchMessages.get();
    }

    long coalescedReactionUpdates() {
        return coalescedReactionUpdates.get();
    }

    private record ReactionUpdateCommand(Query query, Update update) {
    }

    private static final class PendingReactionBatch {
        private final Map<String, MessageReactionResponse> updatesByMessageId = new HashMap<>();
        private volatile ScheduledFuture<?> scheduledFuture;
        private long version = 1;
        private long changeCount;

        private void update(String messageId, Map<String, Set<String>> reactions) {
            updatesByMessageId.put(messageId, new MessageReactionResponse(messageId, reactions));
            this.version++;
            this.changeCount++;
        }

        private long delayMs(long coalesceMs, int maxBatchSize) {
            return updatesByMessageId.size() >= maxBatchSize ? 0 : coalesceMs;
        }

        private void reschedule(ScheduledFuture<?> scheduledFuture) {
            ScheduledFuture<?> previous = this.scheduledFuture;
            this.scheduledFuture = scheduledFuture;
            if (previous != null) {
                previous.cancel(false);
            }
        }

        private List<MessageReactionResponse> responses() {
            return List.copyOf(updatesByMessageId.values());
        }

        private long version() {
            return version;
        }

        private long changeCount() {
            return changeCount;
        }
    }

    private static void updateMax(AtomicLong max, long candidate) {
        long current;
        do {
            current = max.get();
            if (candidate <= current) {
                return;
            }
        } while (!max.compareAndSet(current, candidate));
    }
}
