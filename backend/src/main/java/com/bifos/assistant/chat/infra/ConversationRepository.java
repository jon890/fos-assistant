package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.model.domain.type.ModelTier;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /** 같은 대화의 첨부 upload 를 장수 확인부터 저장까지 하나씩 처리한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from Conversation c
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    Optional<Conversation> findActiveByIdAndUserIdForUpload(@Param("id") Long id, @Param("userId") Long userId);

    /** 메시지 저장과 빈 대화 삭제가 같은 대화 줄을 먼저 잠근다. 목록에서 숨긴 대화도 물리적으로 남아 있으면 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForMessageWrite(@Param("id") Long id);

    /**
     * 공유 잠금으로 읽는다. 대화 삭제가 이 줄을 고치는 동안은 그 커밋을 기다리고, 먼저 잠그면 삭제가 이 트랜잭션의 커밋을 기다린다. 판단 피드백
     * 기록이 삭제와 차례를 맞추는 데 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForShare(@Param("id") Long id);

    /** 메시지 저장이 끝나기를 기다린 뒤 빈 작업 대화만 지운다. 트랜잭션은 ChatService 가 연다. */
    default int discardEmptyTaskConversation(Long id) {
        if (findByIdForMessageWrite(id).isEmpty()) {
            return 0;
        }
        return deleteEmptyTaskConversation(id);
    }

    /** 대화 줄을 잠근 뒤에만 부른다. 조건부 삭제는 메시지가 남은 대화를 보존한다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from Conversation c
             where c.id = :id and c.taskId is not null
               and not exists (select m.id from ChatMessage m where m.conversationId = c.id)
            """)
    int deleteEmptyTaskConversation(@Param("id") Long id);

    Optional<Conversation> findByPublicIdAndUserIdAndDeletedAtIsNull(UUID publicId, Long userId);

    /** 그 사용자의 지우지 않은 대화를 최근 것부터 첫 쪽만큼 읽는다. 개수는 {@code pageable} 이 정한다. */
    List<Conversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    /** {@code (updatedAt, id)} 로 정한 자리 바로 다음 줄부터 읽는다. 정렬은 첫 쪽과 같다. */
    @Query("""
            select c from Conversation c
             where c.userId = :userId and c.deletedAt is null
               and (c.updatedAt < :updatedAt or (c.updatedAt = :updatedAt and c.id < :id))
             order by c.updatedAt desc, c.id desc
            """)
    List<Conversation> findPageAfter(
            @Param("userId") Long userId,
            @Param("updatedAt") Instant updatedAt,
            @Param("id") Long id,
            Pageable pageable);

    /** 그 사용자가 그 에이전트와 나눈 지우지 않은 대화를 최근 것부터 읽는다. 개수는 {@code pageable} 이 정한다. */
    List<Conversation> findByUserIdAndAgentIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            Long userId, Long agentId, Pageable pageable);

    /** 그 사용자가 그 에이전트와 그 목적으로 연 지우지 않은 대화 가운데 가장 나중에 만든 것. 점검 대화를 찾는다. */
    Optional<Conversation> findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(
            Long userId, Long agentId, ConversationPurpose purpose);

    /** 어느 대화가 그 값을 보낼 session 이나 루트 session 으로 쓰는지. 지운 대화도 센다. */
    boolean existsByHermesSessionIdOrHermesRootSessionId(String hermesSessionId, String hermesRootSessionId);

    /** 보낼 session 을 채우고 {@code updatedAt} 을 올린다. session 이 null 이면 있던 값을 둔다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query(
            "update Conversation c set c.hermesSessionId = coalesce(:sessionId, c.hermesSessionId), c.updatedAt = :now where c.id = :id")
    int touchSession(@Param("id") Long id, @Param("sessionId") String sessionId, @Param("now") Instant now);

    /**
     * session 이 비어 있을 때만 보낼 session 과 루트 session 을 같은 값으로 채운다. 채웠으면 1 이다.
     *
     * <p>turn 을 시작할 때 쓰므로 {@code updatedAt} 을 바꾸지 않는다. 바꾸면 실패한 turn 도 대화를 목록 맨
     * 위로 올린다. 같은 새 대화에 두 turn 이 함께 와도 조건 때문에 한쪽만 채운다.
     *
     * <p>트랜잭션은 {@code ConversationWriter} 가 연다.
     */
    @Modifying
    @Query("""
            update Conversation c set c.hermesSessionId = :sessionId, c.hermesRootSessionId = :sessionId
             where c.id = :id and c.hermesSessionId is null
            """)
    int assignSessionIfAbsent(@Param("id") Long id, @Param("sessionId") String sessionId);

    /**
     * 보낼 session 과 루트 session 을 같은 새 값으로 바꾼다. 먼저 살펴보기가 한 session 으로 보낸 수가 상한에 닿았을 때 쓴다.
     *
     * <p>{@code updatedAt} 은 바꾸지 않는다. 트랜잭션은 {@code ConversationWriter} 가 연다.
     */
    @Modifying
    @Query(
            "update Conversation c set c.hermesSessionId = :sessionId, c.hermesRootSessionId = :sessionId where c.id = :id")
    int replaceSessions(@Param("id") Long id, @Param("sessionId") String sessionId);

    /** 사용자의 질문 없이 연 turn 의 수를 0 으로 돌린다. 이미 0 이면 줄을 건드리지 않고 0 을 돌려준다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query("update Conversation c set c.autoTurnCount = 0 where c.id = :id and c.autoTurnCount <> 0")
    int resetAutoTurns(@Param("id") Long id);

    /** 사용자의 질문 없이 연 turn 의 수를 하나 늘린다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query("update Conversation c set c.autoTurnCount = c.autoTurnCount + 1 where c.id = :id")
    int incrementAutoTurns(@Param("id") Long id);

    /** 제목이 비어 있을 때만 채운다. 채웠으면 1 이다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query("update Conversation c set c.title = :title where c.id = :id and c.title = ''")
    int fillTitleIfBlank(@Param("id") Long id, @Param("title") String title);

    /** 그 사용자의 지우지 않은 대화일 때만 제목을 바꾼다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c set c.title = :title, c.updatedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int renameIfActive(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("title") String title,
            @Param("now") Instant now);

    /** 고른 모델과 effort 만 바꾼다. 대화 목록의 순서를 흔들지 않도록 {@code updatedAt} 은 건드리지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c
               set c.modelProvider = :provider, c.model = :model, c.reasoningEffort = :reasoningEffort,
                   c.modelSelectionMode = :customMode,
                   c.modelTier = null
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int chooseModelIfActive(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("reasoningEffort") String reasoningEffort,
            @Param("customMode") ModelSelectionMode customMode);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c set c.modelSelectionMode = :mode, c.modelTier = :tier,
                c.modelProvider = null, c.model = null, c.reasoningEffort = null
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int chooseModelTierIfActive(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("mode") ModelSelectionMode mode,
            @Param("tier") ModelTier tier);

    /** 그 사용자의 지우지 않은 대화일 때만 지운 시각을 적는다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c set c.deletedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int deleteIfActive(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);
}
