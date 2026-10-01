package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.Memory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MemoryRepository extends JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory> {

    Optional<Memory> findByProposalDedupKey(String proposalDedupKey);
}
