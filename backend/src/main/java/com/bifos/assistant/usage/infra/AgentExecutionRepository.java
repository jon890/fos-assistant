package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    List<AgentExecution> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
