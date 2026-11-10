package com.bifos.assistant.chat.conversation.infra;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.model.domain.type.ModelTier;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long>, ConversationWrites<Conversation> {

    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);


    /**
     * 대화의 주인 번호다. 암호화한 메시지를 풀 때 AAD 를 만든다(ADR-20261008 / data-encryption).
     *
     * <p>본문을 꺼내는 자리는 어느 트랜잭션 안이든 될 수 있다. 그 트랜잭션의 미뤄 둔 쓰기를 이 조회가 먼저 내보내지 않게 flush 를
     * 커밋 때로 미룬다.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query("select c.userId from Conversation c where c.id = :id")
    Optional<Long> findOwnerId(@Param("id") Long id);


    /**
     * 지웠지만 본문을 아직 지우지 않은 대화의 번호다. 먼저 지운 대화부터 낸다(ADR-20261008 / conversation-purge).
     *
     * <p>{@code skipped} 는 실패해 기다리는 중인 대화다. 빼고 골라야 그 대화가 쌓여도 뒤에 지운 대화가 밀리지 않는다. 비우면 안 되므로
     * 기다리는 대화가 없으면 없는 번호 하나를 넘긴다.
     */
    @Query("""
            select c.id from Conversation c
             where c.purgedAt is null and c.deletedAt is not null and c.id not in :skipped
             order by c.deletedAt asc, c.id asc
            """)
    List<Long> findPurgeCandidates(@Param("skipped") Collection<Long> skipped, Pageable page);


    /**
     * 본문을 지운 대화로 적는다. 제목과 Hermes session 을 비운다. 줄은 남긴다. 실행 기록과 다른 표가 이 번호를 가리킨다.
     *
     * <p>지운 대화이고 아직 적지 않았을 때만 적는다. 트랜잭션은 {@code ConversationPurgeWriter} 가 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c
               set c.storedTitle = '', c.titleKeyId = null, c.hermesSessionId = null, c.hermesRootSessionId = null, c.purgedAt = :now
             where c.id = :id and c.deletedAt is not null and c.purgedAt is null
            """)
    int markPurged(@Param("id") Long id, @Param("now") Instant now);


    /** 그 에이전트에 사용자가 지웠지만 본문을 아직 지우지 않은 대화가 있는가. 지운 에이전트 정리를 미룰 때 본다(ADR-20261009 / agent-purge). */
    boolean existsByAgentIdAndDeletedAtIsNotNullAndPurgedAtIsNull(Long agentId);


    /** 같은 대화의 첨부 upload 를 장수 확인부터 저장까지 하나씩 처리한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from Conversation c
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    Optional<Conversation> findActiveByIdAndUserIdForUpload(@Param("id") Long id, @Param("userId") Long userId);


    /** 평문으로 남은 제목의 대화 번호를 번호 순으로 읽는다. 빈 제목은 평문 빈 글이 규칙이라 뺀다(ADR-20261008 / data-encryption). */
    @Query("""
            select c.id from Conversation c
            where c.titleKeyId is null and length(c.storedTitle) > 0 and c.id > :after
            order by c.id
            """)
    List<Long> findPlainTitleIds(@Param("after") Long after, Pageable pageable);


    /** 암호문 제목의 대화 번호를 번호 순으로 읽는다. 풀기 배치가 쓴다. */
    @Query("select c.id from Conversation c where c.titleKeyId is not null and c.id > :after order by c.id")
    List<Long> findSealedTitleIds(@Param("after") Long after, Pageable pageable);


    @Query("select count(c) from Conversation c where c.titleKeyId is null and length(c.storedTitle) > 0")
    long countPlainTitles();


    @Query("select count(c) from Conversation c where c.titleKeyId is not null")
    long countSealedTitles();


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


    /** 그 사용자의 지우지 않았고 목록에서 숨기지 않은 대화를 최근 것부터 첫 쪽만큼 읽는다. 개수는 {@code pageable} 이 정한다. */
    List<Conversation> findByUserIdAndDeletedAtIsNullAndHiddenAtIsNullOrderByUpdatedAtDescIdDesc(
        Long userId, Pageable pageable);


    /** {@code (updatedAt, id)} 로 정한 자리 바로 다음 줄부터 읽는다. 정렬과 숨긴 대화를 빼는 것은 첫 쪽과 같다. */
    @Query("""
            select c from Conversation c
             where c.userId = :userId and c.deletedAt is null and c.hiddenAt is null
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


    /**
     * 그 session 이나 루트가 점검 대화의 것인가. 대화의 지금 session 과 루트, 그 profile 의 실행 줄이 적은 session 을 함께 본다. 점검
     * 대화의 session 을 새로 연 뒤에도 옛 session 은 실행 줄에 남아서다. 도구 판정이 도는 실행 없는 호출을 막을지 정한다
     * (ADR-20261105 / event-watch).
     */
    @Query("""
            select case when count(c) > 0 then true else false end
              from Conversation c
             where c.purpose = :purpose
               and (c.hermesSessionId in (:sessionId, :rootSessionId)
                    or c.hermesRootSessionId in (:sessionId, :rootSessionId)
                    or c.id in (select e.conversationId from AgentExecution e
                                 where e.profileName = :profile and e.hermesSessionId in (:sessionId, :rootSessionId)))
            """)
    boolean existsSessionWithPurpose(
        @Param("profile") String profile,
        @Param("sessionId") String sessionId,
        @Param("rootSessionId") String rootSessionId,
        @Param("purpose") ConversationPurpose purpose);


    /** 보낼 session 을 채우고 {@code updatedAt} 을 올린다. session 이 null 이면 있던 값을 둔다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query("update Conversation c set c.hermesSessionId = coalesce(:sessionId, c.hermesSessionId), c.updatedAt = :now where c.id = :id")
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
    @Query("update Conversation c set c.hermesSessionId = :sessionId, c.hermesRootSessionId = :sessionId where c.id = :id")
    int replaceSessions(@Param("id") Long id, @Param("sessionId") String sessionId);


    /** 사용자의 질문 없이 연 turn 의 수를 0 으로 돌린다. 이미 0 이면 줄을 건드리지 않고 0 을 돌려준다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query("update Conversation c set c.autoTurnCount = 0 where c.id = :id and c.autoTurnCount <> 0")
    int resetAutoTurns(@Param("id") Long id);


    /** 사용자의 질문 없이 연 turn 의 수를 하나 늘린다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying
    @Query("update Conversation c set c.autoTurnCount = c.autoTurnCount + 1 where c.id = :id")
    int incrementAutoTurns(@Param("id") Long id);


    /**
     * 제목이 비어 있을 때만 채운다. 채웠으면 1 이다. 트랜잭션은 {@code ConversationWriter} 가 연다.
     *
     * <p>엔티티 리스너를 거치지 않으므로 부르는 쪽이 암호문과 그 데이터 key 번호를 넘긴다. 암호화하지 않으면 {@code titleKeyId} 는 null 이다.
     */
    @Modifying
    @Query("update Conversation c set c.storedTitle = :title, c.titleKeyId = :titleKeyId where c.id = :id and c.storedTitle = ''")
    int fillTitleIfBlank(@Param("id") Long id, @Param("title") String title, @Param("titleKeyId") Long titleKeyId);


    /**
     * 그 사용자의 지우지 않은 대화일 때만 제목을 바꾼다. 트랜잭션은 {@code ConversationWriter} 가 연다.
     *
     * <p>{@code title} 과 {@code titleKeyId} 는 {@link #fillTitleIfBlank} 와 같다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c set c.storedTitle = :title, c.titleKeyId = :titleKeyId, c.updatedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int renameIfActive(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("title") String title,
        @Param("titleKeyId") Long titleKeyId,
        @Param("now") Instant now);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c set c.modelSelectionMode = :mode, c.modelTier = :tier
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int chooseModelTierIfActive(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("mode") ModelSelectionMode mode,
        @Param("tier") ModelTier tier);


    /**
     * 대화가 그 단계를 고르게 한다. 예약 작업 발화가 부르는 경로다.
     *
     * <p>발화 준비 트랜잭션 안에서 불리므로 {@code clearAutomatically} 를 두지 않는다. 영속성 컨텍스트를 비우면 그 뒤 발화 기록의
     * 변경이 반영되지 않는다. 대화 목록의 순서를 흔들지 않도록 {@code updatedAt} 은 바꾸지 않는다. 트랜잭션은 {@code
     * ConversationWriter} 가 연다.
     */
    @Modifying
    @Query("""
            update Conversation c set c.modelSelectionMode = :mode, c.modelTier = :tier
             where c.id = :id
            """)
    int chooseTierForTask(@Param("id") Long id, @Param("mode") ModelSelectionMode mode, @Param("tier") ModelTier tier);


    /**
     * 대화를 목록에서만 뺀다. 이미 숨겼으면 줄을 건드리지 않고 0 을 돌려준다. 예약 작업이 「보고할 것 없음」 으로 끝났을 때 쓴다.
     *
     * <p>발화 결과를 적는 트랜잭션 안에서 불리므로 {@code clearAutomatically} 를 두지 않는다. 대화 목록의 순서를 흔들지 않도록
     * {@code updatedAt} 은 바꾸지 않는다. 트랜잭션은 {@code ConversationWriter} 가 연다.
     */
    @Modifying
    @Query("update Conversation c set c.hiddenAt = :now where c.id = :id and c.hiddenAt is null")
    int hideFromList(@Param("id") Long id, @Param("now") Instant now);


    /**
     * 숨긴 대화를 다시 목록에 보인다. 사용자가 질문하면 부른다. 숨기지 않았으면 줄을 건드리지 않고 0 을 돌려준다.
     *
     * <p>질문 저장 트랜잭션 안에서 불리므로 {@code clearAutomatically} 를 두지 않는다. 트랜잭션은 {@code ConversationWriter}
     * 가 연다.
     */
    @Modifying
    @Query("update Conversation c set c.hiddenAt = null where c.id = :id and c.hiddenAt is not null")
    int showInList(@Param("id") Long id);


    /** 그 사용자의 지우지 않은 대화일 때만 지운 시각을 적는다. 트랜잭션은 {@code ConversationWriter} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Conversation c set c.deletedAt = :now
             where c.id = :id and c.userId = :userId and c.deletedAt is null
            """)
    int deleteIfActive(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);


    /**
     * 사용자 정리 때 그 사용자의 지우지 않은 대화 모두에 지운 시각을 적는다. 숨긴 대화도 포함한다(ADR-20261101 / user-purge).
     *
     * <p>본문은 대화 정리(ADR-20261008 / conversation-purge)가 지운다. 트랜잭션은 부르는 쪽이 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Conversation c set c.deletedAt = :now where c.userId = :userId and c.deletedAt is null")
    int deleteAllActiveOf(@Param("userId") Long userId, @Param("now") Instant now);


    /** 그 사용자의 대화 가운데 아직 본문을 정리하지 않은 수다. 지우지 않은 대화도 센다. */
    long countByUserIdAndPurgedAtIsNull(Long userId);


    /**
     * 대기 줄의 JSON 이다(ADR-048). 비었거나 대화가 없으면 빈 값이다.
     *
     * <p>엔티티는 이 칸을 읽지 않으므로 전용 질의로 읽는다. 바꾸려고 읽을 때는 {@link #lockPendingMessages} 를 쓴다.
     */
    @Query(value = "select pending_messages from conversation where id = :id", nativeQuery = true)
    Optional<String> findPendingMessages(@Param("id") Long id);


    /** 대화 줄을 잠그고 대기 줄의 JSON 을 읽는다. 같은 대화의 대기 줄을 바꾸는 트랜잭션이 차례로 돈다. 트랜잭션은 부르는 쪽이 연다. */
    @Query(value = "select pending_messages from conversation where id = :id for update", nativeQuery = true)
    Optional<String> lockPendingMessages(@Param("id") Long id);


    /** 대기 줄의 JSON 을 바꾼다. null 이면 비운다. {@link #lockPendingMessages} 로 잠근 트랜잭션 안에서만 부른다. */
    @Modifying(flushAutomatically = true)
    @Query(value = "update conversation set pending_messages = :value where id = :id", nativeQuery = true)
    int writePendingMessages(@Param("id") Long id, @Param("value") String value);


    /** 대기 줄이 있는 대화의 번호다. 멈춘 줄도 낸다. 빈 줄은 저장하지 않으므로 보내지 않은 글이 있는 대화만 나온다. */
    @Query(value = "select id from conversation where pending_messages is not null order by id", nativeQuery = true)
    List<Long> findIdsWithPendingMessages();
}
