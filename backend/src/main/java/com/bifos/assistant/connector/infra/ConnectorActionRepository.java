package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ActionDelivery;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorActionRepository extends JpaRepository<ConnectorAction, Long> {

    /** 이관 대상의 번호만 읽는다. 사용자·승인 줄 순서로 잠근 뒤 조건을 다시 확인한다. */
    @Query("""
        select a.id from ConnectorAction a
        where (a.storedArgsJson is not null and a.argsKeyId is null)
           or (a.storedResultText is not null and a.resultKeyId is null)
        order by a.id
        """)
    List<Long> findPlainBodyIds();


    Optional<ConnectorAction> findByDedupeKey(String dedupeKey);


    Optional<ConnectorAction> findByPublicId(UUID publicId);


    /** 호출 허용에 앞서 저장한 판정 줄로 자식과 형제를 포함한 트리의 커넥터 호출 이력을 확인한다. */
    @Query("""
            select case when count(a) > 0 then true else false end
            from ConnectorAction a, AgentExecution e
            where a.originExecutionId = e.id and a.userId = e.userId
                and (e.id = :rootId or e.rootExecutionId = :rootId)
                and a.createdAt <= :at
            """)
    boolean existsCallInTreeBefore(@Param("rootId") Long rootId, @Param("at") Instant at);


    /**
     * 승인 줄의 상태를 바꾸기 전에 그 줄을 잠그고 읽는다.
     *
     * <p>같은 줄의 승인과 거절과 만료와 연결 해제가 순서대로 처리된다. 뒤에 온 쪽은 앞선 쪽이 커밋한 상태를 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ConnectorAction a where a.publicId = :publicId")
    Optional<ConnectorAction> findByPublicIdForUpdate(@Param("publicId") UUID publicId);


    /** 연결을 해제하거나 값을 다시 등록할 때 그 연결의 답을 기다리는 줄을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from ConnectorAction a
            where a.userId = :userId and a.connectorId = :connectorId and a.status = :status
            order by a.id
            """)
    List<ConnectorAction> findByUserIdAndConnectorIdAndStatusForUpdate(
        @Param("userId") Long userId,
        @Param("connectorId") String connectorId,
        @Param("status") ActionStatus status);


    /** 같은 실행이 같은 도구를 같은 인자로 다시 불렀는지 본다. */
    Optional<ConnectorAction> findFirstByOriginExecutionIdAndHermesToolAndArgsSha256AndStatusOrderByIdAsc(
        Long originExecutionId, String hermesTool, String argsSha256, ActionStatus status);


    List<ConnectorAction> findByConversationIdAndUserIdAndStatusOrderByIdAsc(
        Long conversationId, Long userId, ActionStatus status);


    /** 그 상태가 아닌 승인 줄이다. 승인 줄이 아닌 판정 줄은 상태가 비어 있어 걸리지 않는다. */
    List<ConnectorAction> findTop20ByConversationIdAndUserIdAndStatusNotOrderByIdDesc(
        Long conversationId, Long userId, ActionStatus status);


    List<ConnectorAction> findByStatusAndExpiresAtBefore(ActionStatus status, Instant now);


    /** 그 사용자가 그 커넥터의 그 도구로 남긴, 결과 글이 있는 줄이다. 재시도 식별자를 남긴 UNKNOWN 줄을 찾는다. */
    List<ConnectorAction> findByUserIdAndConnectorIdAndHermesToolAndStatusAndStoredResultTextIsNotNull(
        Long userId, String connectorId, String hermesTool, ActionStatus status);


    /** 그 사용자의 그 상태인 줄이다. 만든 순이다. 먼저 알리기가 답을 기다리는 줄을 읽는다. */
    List<ConnectorAction> findByUserIdAndStatusOrderByIdAsc(Long userId, ActionStatus status);


    /** 살펴보기 트리의 실행이 만든 승인 대기 카드만 보고에 싣는다. */
    @Query("""
            select a.publicId from ConnectorAction a
            where a.status = :status and exists (
                select e.id from AgentExecution e
                where e.id = a.originExecutionId and e.userId = a.userId
                  and (e.id = :rootId or e.rootExecutionId = :rootId)
            )
            order by a.id
            """)
    List<UUID> findPublicIdsForTree(@Param("rootId") Long rootId, @Param("status") ActionStatus status);


    /** 일반 정리 대상인 실행 줄이다. 원고 job 링크가 있는 줄은 job 정리가 끝내므로 뺀다. */
    @Query("""
            select a from ConnectorAction a
            where a.status = :status
              and not exists (select j.id from ContentDraftJob j where j.actionId = a.id)
            """)
    List<ConnectorAction> findByStatusWithoutContentJob(@Param("status") ActionStatus status);


    /** {@link #findByStatusWithoutContentJob(ActionStatus)} 가운데 이 시각보다 먼저 승인한 줄이다. */
    @Query("""
            select a from ConnectorAction a
            where a.status = :status
              and a.decidedAt < :before
              and not exists (select j.id from ContentDraftJob j where j.actionId = a.id)
            """)
    List<ConnectorAction> findByStatusWithoutContentJobDecidedBefore(
        @Param("status") ActionStatus status, @Param("before") Instant before);


    /** 그 대화에서 아직 전하지 않은 끝난 승인 줄이다. 만든 순이다. */
    List<ConnectorAction> findByConversationIdAndStatusInAndResultDeliveredAtIsNullOrderByIdAsc(
        Long conversationId, Collection<ActionStatus> statuses);


    /** 그 대화와 그 사용자의 승인 줄 가운데 그 번호들이다. 순서는 정하지 않는다. */
    List<ConnectorAction> findByConversationIdAndUserIdAndPublicIdIn(
        Long conversationId, Long userId, Collection<UUID> publicIds);


    /** 전하지 않은 끝난 승인 줄이 있는 대화들이다. 대화 없이 돈 실행의 줄은 뺀다. */
    @Query("""
            select distinct a.conversationId from ConnectorAction a
            where a.status in :statuses and a.resultDeliveredAt is null and a.conversationId is not null
            """)
    List<Long> findConversationsWithUndelivered(@Param("statuses") Collection<ActionStatus> statuses);


    /**
     * 아직 전하지 않은 줄에만 전한 시각을 적는다.
     *
     * @return 적은 줄 수. 이미 전한 줄은 세지 않는다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ConnectorAction a set a.resultDeliveredAt = :now
            where a.publicId in :publicIds and a.resultDeliveredAt is null
            """)
    int markDelivered(@Param("publicIds") Collection<UUID> publicIds, @Param("now") Instant now);


    /** 지운 에이전트를 정리할 때 그 에이전트를 가리키는 줄의 에이전트를 비운다. 승인 이력이므로 줄은 남긴다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ConnectorAction a set a.agentId = null where a.agentId = :agentId")
    int clearAgentOf(@Param("agentId") Long agentId);


    /** 사용자·대화 잠금 안에서 개인 본문과 key 참조만 지운다. 감사와 중복 방지 칸은 바꾸지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ConnectorAction a
            set a.storedArgsJson = null, a.argsKeyId = null,
                a.storedResultText = null, a.resultKeyId = null, a.bodyErasedAt = :now
            where a.id in :ids and a.bodyErasedAt is null
            """)
    int eraseBodiesOf(@Param("ids") Collection<Long> ids, @Param("now") Instant now);


    /** 열린 승인과 복구용 UNKNOWN, 당일 금융 합계에 필요한 성공 줄은 본문을 남긴다. */
    @Query("""
            select a.id from ConnectorAction a
            where a.conversationId = :conversationId and a.bodyErasedAt is null
                and (a.status is null or a.status not in :open)
                and (a.risk is null or a.risk <> :financial or a.status is null or a.status <> :succeeded
                    or a.decidedAt is null or a.decidedAt < :dayStart)
            order by a.id
            """)
    List<Long> findErasableBodyIds(@Param("conversationId") Long conversationId,
        @Param("open") Collection<ActionStatus> open, @Param("financial") ToolRisk financial,
        @Param("succeeded") ActionStatus succeeded, @Param("dayStart") Instant dayStart);


    /** 본문 삭제 도입 전에 purge한 대화에서 아직 지우지 않은 승인 본문을 찾는다. */
    @Query("""
            select distinct a.conversationId from ConnectorAction a, Conversation c
            where a.conversationId = c.id and c.deletedAt is not null and c.purgedAt is not null
                and a.bodyErasedAt is null and a.conversationId not in :skipped
                and (a.status is null or a.status not in :open)
                and (a.risk is null or a.risk <> :financial or a.status is null or a.status <> :succeeded
                    or a.decidedAt is null or a.decidedAt < :dayStart)
            order by a.conversationId
            """)
    List<Long> findPurgedConversationsWithBodies(@Param("skipped") List<Long> skipped,
        @Param("open") Collection<ActionStatus> open, @Param("financial") ToolRisk financial,
        @Param("succeeded") ActionStatus succeeded, @Param("dayStart") Instant dayStart, Pageable pageable);


    /** 그 연결에 그 상태의 승인 줄이 있는가. */
    boolean existsByUserIdAndConnectorIdAndStatus(Long userId, String connectorId, ActionStatus status);


    /** 그 연결에서 그 에이전트의 실행이 판정한 줄 가운데 그 상태인 것이다. 연결을 에이전트에서 뗄 때 고른다. */
    List<ConnectorAction> findByUserIdAndConnectorIdAndAgentIdAndStatus(
        Long userId, String connectorId, Long agentId, ActionStatus status);


    /** 대화마다 그 상태인 승인 줄의 결과를 전한 가장 늦은 시각이다. 전한 줄이 없는 대화는 나오지 않는다. */
    @Query("""
            select a.conversationId as conversationId, max(a.resultDeliveredAt) as deliveredAt
            from ConnectorAction a
            where a.conversationId in :conversationIds and a.status in :statuses and a.resultDeliveredAt is not null
            group by a.conversationId
            """)
    List<ActionDelivery> findLastDeliveredByConversation(
        @Param("conversationIds") Collection<Long> conversationIds,
        @Param("statuses") Collection<ActionStatus> statuses);


    /** 금융 하루 상한이 KST 하루 안에 승인으로 넘어간 금융 줄을 읽는다. 호출자가 상태로 다시 거른다. */
    List<ConnectorAction> findByUserIdAndRiskAndDecidedAtGreaterThanEqual(Long userId, ToolRisk risk, Instant from);
}
