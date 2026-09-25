package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.Agent;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByCode(String code);
    List<Agent> findByEnabledTrueOrderByCodeAsc();

    /**
     * 에이전트 한 줄을 쓰기 잠금으로 읽는다.
     *
     * <p>그 에이전트에 딸린 줄을 지우고 다시 넣는 쓰기를 한 번에 하나씩 돌리려고 쓴다. 트랜잭션이 끝날 때까지
     * 같은 에이전트를 잠그려는 다른 요청은 기다린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.id = :id")
    Optional<Agent> findByIdForUpdate(@Param("id") Long id);

    /**
     * 그 profile 을 이미 가리키는 에이전트가 있는가.
     *
     * <p>사람을 더할 때 profile 이름이 비는지 보는 자리가 둘이고 이것이 그 하나다. 두 사람이 같은
     * profile 을 쓰면 격리가 깨진다.
     */
    boolean existsByHermesProfile(String hermesProfile);
}
