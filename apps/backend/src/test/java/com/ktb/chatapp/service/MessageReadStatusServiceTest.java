package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReadStatusServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private MongoConverter mongoConverter;

    @Test
    void updateReadStatus_updatesUnreadMessagesInOneMongoOperation() {
        MessageReadStatusService service = new MessageReadStatusService(mongoTemplate);
        when(mongoTemplate.getConverter()).thenReturn(mongoConverter);
        when(mongoConverter.convertToMongoType(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Document("userId", "user-1"));
        when(mongoTemplate.updateMulti(
                org.mockito.ArgumentMatchers.any(Query.class),
                org.mockito.ArgumentMatchers.any(AggregationUpdate.class),
                eq(Message.class)))
                .thenReturn(UpdateResult.acknowledged(2L, 2L, null));

        long modifiedCount = service.updateReadStatus(
                "room-1",
                List.of("message-1", "message-2", "message-1"),
                "user-1");

        assertThat(modifiedCount).isEqualTo(2);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<AggregationUpdate> updateCaptor = ArgumentCaptor.forClass(AggregationUpdate.class);
        verify(mongoTemplate).updateMulti(queryCaptor.capture(), updateCaptor.capture(), eq(Message.class));

        Document query = queryCaptor.getValue().getQueryObject();
        assertThat(query.get("_id", Document.class).get("$in")).isEqualTo(List.of("message-1", "message-2"));
        assertThat(query.get("room")).isEqualTo("room-1");
        assertThat(query.toJson()).contains("readers").contains("userId").contains("user-1");
        assertThat(updateCaptor.getValue()).isNotNull();
    }

    @Test
    void updateReadStatus_skipsEmptyInput() {
        MessageReadStatusService service = new MessageReadStatusService(mongoTemplate);

        long modifiedCount = service.updateReadStatus("room-1", List.of(), "user-1");

        assertThat(modifiedCount).isZero();
        verify(mongoTemplate, never()).updateMulti(
                org.mockito.ArgumentMatchers.any(Query.class),
                org.mockito.ArgumentMatchers.any(AggregationUpdate.class),
                eq(Message.class));
    }
}
