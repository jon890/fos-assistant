package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.AgentToolsetRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentToolsetRequestRepository extends JpaRepository<AgentToolsetRequest, Long> {
    Optional<AgentToolsetRequest> findByPublicId(UUID publicId);

    @Query("select r.agentId from AgentToolsetRequest r where r.publicId = :publicId")
    Optional<Long> findAgentIdByPublicId(@Param("publicId") UUID publicId);

    List<AgentToolsetRequest> findByAgentIdAndGroupIdOrderByRequestedAtDescIdDesc(Long agentId, Long groupId);

    Optional<AgentToolsetRequest> findByAgentIdAndGroupIdAndRequesterUserIdAndToolsetAndPendingSlot(
            Long agentId, Long groupId, Long requesterUserId, String toolset, Integer pendingSlot);
}
