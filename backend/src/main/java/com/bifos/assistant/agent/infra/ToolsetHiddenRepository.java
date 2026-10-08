package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.ToolsetHidden;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolsetHiddenRepository extends JpaRepository<ToolsetHidden, Long> {
    List<ToolsetHidden> findByGroupIdOrderByNameAsc(Long groupId);

    void deleteByGroupId(Long groupId);
}
