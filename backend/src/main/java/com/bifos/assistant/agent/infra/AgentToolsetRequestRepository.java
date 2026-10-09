package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentToolsetRequest;
import com.bifos.assistant.agent.domain.type.ToolsetRequestStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentToolsetRequestRepository extends JpaRepository<AgentToolsetRequest, Long> {
    Optional<AgentToolsetRequest> findByPublicId(UUID publicId);

    /** 잠금을 기다린 뒤에도 MySQL의 이전 읽기 시점이 아닌 현재 요청 상태를 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentToolsetRequest r where r.publicId = :publicId")
    Optional<AgentToolsetRequest> findByPublicIdForUpdate(@Param("publicId") UUID publicId);

    @Query("select r.requesterUserId from AgentToolsetRequest r where r.publicId = :publicId")
    Optional<Long> findRequesterUserIdByPublicId(@Param("publicId") UUID publicId);

    @Query("select r.agentId from AgentToolsetRequest r where r.publicId = :publicId")
    Optional<Long> findAgentIdByPublicId(@Param("publicId") UUID publicId);

    /** 대기 상태일 때만 결정하고 영속성 문맥을 비워 갱신한 상태를 다시 읽게 한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AgentToolsetRequest r
            set r.status = :status, r.pendingSlot = null, r.decidedByUserId = :decider,
                r.reason = :reason, r.decidedAt = :now
            where r.publicId = :publicId and r.status = 'PENDING'
            """)
    int finishPending(
            @Param("publicId") UUID publicId,
            @Param("status") ToolsetRequestStatus status,
            @Param("decider") Long decider,
            @Param("reason") String reason,
            @Param("now") Instant now);

    List<AgentToolsetRequest> findByAgentIdAndGroupIdOrderByRequestedAtDescIdDesc(Long agentId, Long groupId);

    Optional<AgentToolsetRequest> findByAgentIdAndGroupIdAndRequesterUserIdAndToolsetAndPendingSlot(
            Long agentId, Long groupId, Long requesterUserId, String toolset, Integer pendingSlot);
}
