package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.Memory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryRepository extends JpaRepository<Memory, Long> {
    Optional<Memory> findByProposalDedupKey(String proposalDedupKey);
}
