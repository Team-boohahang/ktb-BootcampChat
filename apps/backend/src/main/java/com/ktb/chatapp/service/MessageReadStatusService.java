package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.ArrayOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.LiteralOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    static final int UPDATE_BATCH_SIZE = 500;

    private final MongoTemplate mongoTemplate;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * @param roomId 메시지가 속한 채팅방 ID
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     * @return 모든 배치 갱신이 성공했으면 {@code true}, 하나라도 실패했으면 {@code false}
     */
    public boolean updateReadStatus(String roomId, List<String> messageIds, String userId) {
        if (!StringUtils.hasText(roomId) || !StringUtils.hasText(userId)
                || messageIds == null || messageIds.isEmpty()) {
            return true;
        }

        List<String> uniqueMessageIds = new ArrayList<>(new LinkedHashSet<>(
                messageIds.stream()
                        .filter(StringUtils::hasText)
                        .toList()));
        if (uniqueMessageIds.isEmpty()) {
            return true;
        }

        var readerInfo = Message.MessageReader.builder()
                .userId(userId)
                .readAt(LocalDateTime.now())
                .build();

        try {
            long modifiedCount = 0;
            for (int start = 0; start < uniqueMessageIds.size(); start += UPDATE_BATCH_SIZE) {
                int end = Math.min(start + UPDATE_BATCH_SIZE, uniqueMessageIds.size());
                modifiedCount += updateBatch(
                        roomId, uniqueMessageIds.subList(start, end), userId, readerInfo);
            }
            log.debug("Read status updated for {} of {} messages by user {} in room {}",
                    modifiedCount, uniqueMessageIds.size(), userId, roomId);
            return true;
        } catch (Exception e) {
            log.error("Read status update error for user {} in room {}", userId, roomId, e);
            return false;
        }
    }

    private long updateBatch(
            String roomId,
            List<String> messageIds,
            String userId,
            Message.MessageReader readerInfo) {
        Query query = Query.query(Criteria.where("_id").in(messageIds)
                .and("room").is(roomId)
                .and("readers").not()
                .elemMatch(Criteria.where("userId").is(userId)));

        var existingReaders =
                ConditionalOperators.ifNull("readers").then(List.of());
        Object readerDocument = mongoTemplate.getConverter().convertToMongoType(readerInfo);
        var newReader =
                LiteralOperators.Literal.asLiteral(List.of(readerDocument));
        AggregationUpdate update = AggregationUpdate.update()
                .set("readers")
                .toValue(ArrayOperators.ConcatArrays.arrayOf(existingReaders)
                        .concat(newReader));

        return mongoTemplate.updateMulti(query, update, Message.class).getModifiedCount();
    }
}
