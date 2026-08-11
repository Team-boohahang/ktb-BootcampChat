package com.ktb.chatapp.service.recentmessage;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * 최근 메시지의 MongoDB fallback 집계와 bounded cursor 조회를 담당한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageMongoStore {

    public static final int HYDRATION_BATCH_SIZE = 500;

    private final MongoTemplate mongoTemplate;

    /**
     * 메시지 문서를 애플리케이션으로 가져오지 않고 MongoDB에서 방별 개수를 집계한다.
     */
    public Map<String, Integer> countAll(Collection<String> roomIds, LocalDateTime since) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(recentCriteria(roomIds, since)),
                Aggregation.group("room").count().as("count"));

        Map<String, Integer> counts = new LinkedHashMap<>();
        mongoTemplate.aggregate(aggregation, "messages", Document.class)
                .forEach(result -> {
                    String roomId = result.getString("_id");
                    Number count = result.get("count", Number.class);
                    if (roomId != null && count != null) {
                        counts.put(roomId, Math.toIntExact(count.longValue()));
                    }
                });
        return counts;
    }

    /**
     * MongoDB cursor가 한 번에 최대 500개만 가져오도록 제한한다.
     * 호출자는 반환된 Stream을 반드시 닫아야 한다.
     */
    public Stream<Message> streamRecentMessages(
            Collection<String> roomIds,
            LocalDateTime since) {
        Query query = Query.query(recentCriteria(roomIds, since));
        query.fields().include("_id").include("room").include("timestamp");
        query.cursorBatchSize(HYDRATION_BATCH_SIZE);
        return mongoTemplate.stream(query, Message.class);
    }

    private Criteria recentCriteria(Collection<String> roomIds, LocalDateTime since) {
        return Criteria.where("room").in(roomIds)
                .and("timestamp").gte(since);
    }
}
