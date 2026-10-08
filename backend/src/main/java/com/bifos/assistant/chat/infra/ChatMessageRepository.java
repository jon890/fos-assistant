package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.TurnTiming;
import com.bifos.assistant.chat.domain.UserLastMessage;
import com.bifos.assistant.chat.domain.type.MessageRole;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
                and m.role = com.bifos.assistant.chat.domain.type.MessageRole.ASSISTANT
                and m.executionId is not null
            order by m.executionId
            """)
    List<Long> findAssistantExecutionIds(@Param("conversationId") Long conversationId);

    /** 대화에서 그 역할이 처음 남긴 메시지를 읽는다. */
    Optional<ChatMessage> findFirstByConversationIdAndRoleOrderByIdAsc(Long conversationId, MessageRole role);

    /** 그 시각까지 그 대화에 저장된 메시지 가운데 그 역할이 아닌 가장 최근 메시지를 읽는다. */
    Optional<ChatMessage> findTopByConversationIdAndRoleNotAndCreatedAtLessThanEqualOrderByIdDesc(
            Long conversationId, MessageRole role, Instant createdAt);

    /** 대화에서 그 역할이 그 시각 뒤에 남긴 메시지 수. 먼저 살펴보기가 지난 살펴보기 뒤 사용자가 보낸 메시지를 센다. */
    long countByConversationIdAndRoleAndCreatedAtAfter(Long conversationId, MessageRole role, Instant after);

    /** 그 실행이 남긴 메시지가 이미 있는지 본다. */
    boolean existsByExecutionId(Long executionId);

    /**
     * 기간 안에 요청을 받은 사용자 turn 의 루트 실행에서 첫 반응 시간 집계가 쓰는 칸만 읽는다.
     *
     * <p>사용자 turn 인지는 그 실행의 답 메시지보다 앞선 메시지 가운데 {@code ASSISTANT} 가 아닌 가장 최근 줄의 역할로 가린다.
     * {@code USER} 면 사용자 turn 이고 {@code SYSTEM} 이면 자동 turn 이다. 답 메시지가 없는 실행은 가릴 수 없어 빠진다.
     */
    @Query("""
            select new com.bifos.assistant.chat.domain.TurnTiming(
                e.id, e.requestReceivedAt, e.submittedAt, e.firstDeltaAt, e.modelTier)
            from AgentExecution e, ChatMessage a
            where a.executionId = e.id
                and a.role = com.bifos.assistant.chat.domain.type.MessageRole.ASSISTANT
                and e.userId = :userId
                and e.conversationId is not null
                and e.parentExecutionId is null
                and e.requestReceivedAt >= :from
                and e.requestReceivedAt < :to
                and (select q.role from ChatMessage q
                        where q.id = (select max(p.id) from ChatMessage p
                            where p.conversationId = a.conversationId
                                and p.id < a.id
                                and p.role <> com.bifos.assistant.chat.domain.type.MessageRole.ASSISTANT))
                    = com.bifos.assistant.chat.domain.type.MessageRole.USER
            """)
    List<TurnTiming> findUserTurnTimings(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select new com.bifos.assistant.chat.domain.UserLastMessage(message.senderUserId, max(message.createdAt))
            from ChatMessage message
            where message.role = com.bifos.assistant.chat.domain.type.MessageRole.USER
                and message.senderUserId in :userIds
            group by message.senderUserId
            """)
    List<UserLastMessage> findLastUserMessages(@Param("userIds") Collection<Long> userIds);
}
