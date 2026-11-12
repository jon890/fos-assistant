package com.bifos.assistant.chat.conversation.infra;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.TurnTiming;
import com.bifos.assistant.chat.conversation.domain.UserLastMessage;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long>, ChatMessageWrites<ChatMessage> {

    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);


    /**
     * 대화의 답 메시지가 가리키는 실행 번호를 번호 순으로 읽는다. 메시지 본문은 읽지 않는다.
     *
     * <p>실행이 없는 답 줄은 뺀다. 같은 실행의 답이 여럿이어도 한 번씩만 낸다.
     */
    @Query("""
            select distinct m.executionId from ChatMessage m
            where m.conversationId = :conversationId
                and m.role = com.bifos.assistant.chat.conversation.domain.type.MessageRole.ASSISTANT
                and m.executionId is not null
            order by m.executionId
            """)
    List<Long> findAssistantExecutionIds(@Param("conversationId") Long conversationId);


    /** 대화에서 그 역할이 처음 남긴 메시지를 읽는다. */
    Optional<ChatMessage> findFirstByConversationIdAndRoleOrderByIdAsc(Long conversationId, MessageRole role);


    /** 그 대화의 그 역할 줄을 최근 것부터 읽는다. 개수는 {@code pageable} 이 정한다. 대화방이 최근 알림 줄에서 요약을 찾는다. */
    List<ChatMessage> findByConversationIdAndRoleOrderByIdDesc(Long conversationId, MessageRole role, Pageable pageable);


    /** 그 시각까지 그 대화에 저장된 메시지 가운데 그 역할이 아닌 가장 최근 메시지를 읽는다. */
    Optional<ChatMessage> findTopByConversationIdAndRoleNotAndCreatedAtLessThanEqualOrderByIdDesc(
        Long conversationId, MessageRole role, Instant createdAt);


    /** 대화에서 그 역할이 그 시각 뒤에 남긴 메시지 수. 먼저 살펴보기가 지난 살펴보기 뒤 사용자가 보낸 메시지를 센다. */
    long countByConversationIdAndRoleAndCreatedAtAfter(Long conversationId, MessageRole role, Instant after);


    /** 그 실행이 남긴 메시지가 이미 있는지 본다. */
    boolean existsByExecutionId(Long executionId);


    boolean existsByConversationId(Long conversationId);


    /**
     * 기간 안에 요청을 받은 사용자 turn 의 루트 실행에서 첫 반응 시간 집계가 쓰는 칸만 읽는다.
     *
     * <p>사용자 turn 인지는 그 실행의 답 메시지보다 앞선 메시지 가운데 {@code ASSISTANT} 가 아닌 가장 최근 줄의 역할로 가린다.
     * {@code USER} 면 사용자 turn 이고 {@code SYSTEM} 이면 자동 turn 이다. 답 메시지가 없는 실행은 가릴 수 없어 빠진다.
     */
    @Query("""
            select new com.bifos.assistant.chat.conversation.domain.TurnTiming(
                e.id, e.requestReceivedAt, e.submittedAt, e.firstDeltaAt, e.modelTier)
            from AgentExecution e, ChatMessage a
            where a.executionId = e.id
                and a.role = com.bifos.assistant.chat.conversation.domain.type.MessageRole.ASSISTANT
                and e.userId = :userId
                and e.conversationId is not null
                and e.parentExecutionId is null
                and e.requestReceivedAt >= :from
                and e.requestReceivedAt < :to
                and (select q.role from ChatMessage q
                        where q.id = (select max(p.id) from ChatMessage p
                            where p.conversationId = a.conversationId
                                and p.id < a.id
                                and p.role <> com.bifos.assistant.chat.conversation.domain.type.MessageRole.ASSISTANT))
                    = com.bifos.assistant.chat.conversation.domain.type.MessageRole.USER
            """)
    List<TurnTiming> findUserTurnTimings(
        @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);


    /**
     * 그 대화의 메시지가 앞선 판을 가리키는 칸을 비운다. 같은 대화의 메시지를 지우기 전에 부른다.
     *
     * <p>{@code replaces_message_id} 의 외래 키가 지우는 순서에 따라 한 문장 삭제를 막을 수 있어서다. 트랜잭션은
     * {@code ConversationPurgeWriter} 가 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatMessage m set m.replacesMessageId = null where m.conversationId = :conversationId")
    int clearReplacesOf(@Param("conversationId") Long conversationId);


    /** 그 대화의 메시지를 모두 지운다. 트랜잭션은 {@code ConversationPurgeWriter} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatMessage m where m.conversationId = :conversationId")
    int deleteAllOf(@Param("conversationId") Long conversationId);


    /** 평문으로 남은 메시지의 번호를 번호 순으로 읽는다. 빈 본문은 암호화할 것이 없어 뺀다(ADR-20261008 / data-encryption). */
    @Query("""
            select m.id from ChatMessage m
            where m.contentKeyId is null and length(m.storedContent) > 0 and m.id > :after
            order by m.id
            """)
    List<Long> findPlainIds(@Param("after") Long after, Pageable pageable);


    /** 암호문으로 저장된 메시지의 번호를 번호 순으로 읽는다. 풀기 배치가 쓴다. */
    @Query("select m.id from ChatMessage m where m.contentKeyId is not null and m.id > :after order by m.id")
    List<Long> findSealedIds(@Param("after") Long after, Pageable pageable);


    @Query("select count(m) from ChatMessage m where m.contentKeyId is null and length(m.storedContent) > 0")
    long countPlain();


    @Query("select count(m) from ChatMessage m where m.contentKeyId is not null")
    long countSealed();


    /**
     * 메시지 줄을 쓰기 잠금으로 읽는다. 대화 줄을 먼저 잡은 트랜잭션이 쓴다. 잠금 읽기는 가장 최근 커밋을 읽으므로, 이 트랜잭션 앞쪽의 일반 읽기가
     * 잡은 MySQL 스냅샷의 옛 값을 보지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChatMessage m where m.id = :id")
    Optional<ChatMessage> findByIdForUpdate(@Param("id") Long id);


    /** 메시지 줄을 잠그기 전에 어느 대화 줄을 먼저 잠글지 알아낸다. 본문은 읽지 않는다. */
    @Query("select m.conversationId from ChatMessage m where m.id = :id")
    Optional<Long> findConversationId(@Param("id") Long id);


    @Query("""
            select new com.bifos.assistant.chat.conversation.domain.UserLastMessage(message.senderUserId, max(message.createdAt))
            from ChatMessage message
            where message.role = com.bifos.assistant.chat.conversation.domain.type.MessageRole.USER
                and message.senderUserId in :userIds
            group by message.senderUserId
            """)
    List<UserLastMessage> findLastUserMessages(@Param("userIds") Collection<Long> userIds);
}
