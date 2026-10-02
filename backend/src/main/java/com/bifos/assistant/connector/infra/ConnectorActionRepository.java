package com.bifos.assistant.connector.infra;

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

    List<ConnectorAction> findByStatus(ActionStatus status);

    /** 그 대화에서 아직 전하지 않은 끝난 승인 줄이다. 만든 순이다. */
    List<ConnectorAction> findByConversationIdAndStatusInAndResultDeliveredAtIsNullOrderByIdAsc(
            Long conversationId, Collection<ActionStatus> statuses);

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
}
