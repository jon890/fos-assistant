package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ActionDelivery;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorActionRepository extends JpaRepository<ConnectorAction, Long> {

    Optional<ConnectorAction> findByDedupeKey(String dedupeKey);

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

    /** 그 사용자의 그 상태인 줄이다. 만든 순이다. 먼저 알리기가 답을 기다리는 줄을 읽는다. */
    List<ConnectorAction> findByUserIdAndStatusOrderByIdAsc(Long userId, ActionStatus status);

    List<ConnectorAction> findByStatus(ActionStatus status);

    List<ConnectorAction> findByStatusAndDecidedAtBefore(ActionStatus status, Instant before);

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
}
