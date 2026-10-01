package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.domain.type.ModelTier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Conversation> findByPublicIdAndUserIdAndDeletedAtIsNull(UUID publicId, Long userId);

    List<Conversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    /** 그 사용자가 그 에이전트와 나눈 지우지 않은 대화를 최근 것부터 읽는다. 개수는 {@code pageable} 이 정한다. */
    List<Conversation> findByUserIdAndAgentIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            Long userId, Long agentId, Pageable pageable);

    /** 어느 대화가 그 값을 보낼 session 이나 뿌리 session 으로 쓰는지. 지운 대화도 센다. */
    boolean existsByHermesSessionIdOrHermesRootSessionId(String hermesSessionId, String hermesRootSessionId);

    @Modifying
    @Transactional
    @Query(
            "update Conversation c set c.hermesSessionId = coalesce(:sessionId, c.hermesSessionId), c.updatedAt = :now where c.id = :id")
    int touchSession(@Param("id") Long id, @Param("sessionId") String sessionId, @Param("now") Instant now);

    /**
     * session 이 비어 있을 때만 보낼 session 과 뿌리 session 을 같은 값으로 채운다. 채웠으면 1 이다.
     *
     * <p>turn 을 시작할 때 쓰므로 {@code updatedAt} 을 바꾸지 않는다. 바꾸면 실패한 turn 도 대화를 목록 맨
     * 위로 올린다. 같은 새 대화에 두 turn 이 함께 와도 조건 때문에 한쪽만 채운다.
     */
    @Modifying
    @Transactional
    @Query("""
            update Conversation c set c.hermesSessionId = :sessionId, c.hermesRootSessionId = :sessionId
             where c.id = :id and c.hermesSessionId is null
            """)
    int assignSessionIfAbsent(@Param("id") Long id, @Param("sessionId") String sessionId);

    /** 사용자의 질문 없이 연 turn 의 수를 0 으로 돌린다. 이미 0 이면 줄을 건드리지 않고 0 을 돌려준다. */
    @Modifying
    @Transactional
    @Query("update Conversation c set c.autoTurnCount = 0 where c.id = :id and c.autoTurnCount <> 0")
    int resetAutoTurns(@Param("id") Long id);

    /** 사용자의 질문 없이 연 turn 의 수를 하나 늘린다. */
    @Modifying
    @Transactional
    @Query("update Conversation c set c.autoTurnCount = c.autoTurnCount + 1 where c.id = :id")
    int incrementAutoTurns(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update Conversation c set c.title = :title where c.id = :id and c.title = ''")
    int fillTitleIfBlank(@Param("id") Long id, @Param("title") String title);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
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

    /** 기존 호출은 직접 선택으로 보존한다. */
    default int chooseModelIfActive(Long id, Long userId, String provider, String model, String reasoningEffort) {
        return chooseModelIfActive(id, userId, provider, model, reasoningEffort, ModelSelectionMode.CUSTOM);
    }

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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            update Conversation c set c.deletedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int deleteIfActive(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);
}
