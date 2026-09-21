package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.Agent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByCode(String code);
    List<Agent> findByEnabledTrueOrderByCodeAsc();

    /**
     * 그 profile 을 이미 가리키는 에이전트가 있는가.
     *
     * <p>사람을 더할 때 profile 이름이 비는지 보는 자리가 둘이고 이것이 그 하나다. 두 사람이 같은
     * profile 을 쓰면 격리가 깨진다.
     */
    boolean existsByHermesProfile(String hermesProfile);
}
