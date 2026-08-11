package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
    Page<Message> findByRoomIdAndTimestampBefore(String roomId, LocalDateTime timestamp, Pageable pageable);
    Optional<Message> findBySenderIdAndClientMessageId(String senderId, String clientMessageId);
    long countBySenderIdAndClientMessageId(String senderId, String clientMessageId);

    /**
     * 특정 시간 이후의 메시지 수 카운트
     * 최근 N분간 메시지 수를 조회할 때 사용
     */
    @Query(value = "{ 'room': ?0, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);

    /**
     * Redis가 비어 있거나 장애인 경우 여러 방을 MongoDB 한 번으로 초기화/집계한다.
     * 기존 집계 의미와 동일하게 메시지 type을 제한하지 않는다.
     */
    @Query(
        value = "{ 'room': { $in: ?0 }, 'timestamp': { $gte: ?1 } }",
        fields = "{ '_id': 1, 'room': 1, 'timestamp': 1 }")
    List<Message> findRecentMessagesByRoomIds(Collection<String> roomIds, LocalDateTime since);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);
}
