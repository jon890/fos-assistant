package com.bifos.assistant.agent.infra;

import com.bifos.assistant.agent.domain.Agent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByCode(String code);

    List<Agent> findByEnabledTrueOrderByCodeAsc();

    List<Agent> findByDeletedAtIsNullAndConnectorManagedFalseOrderByCodeAsc();

    /**
     * 에이전트 한 줄을 쓰기 잠금으로 읽는다.
     *
     * <p>그 에이전트에 딸린 줄을 지우고 다시 넣는 쓰기를 한 번에 하나씩 돌리려고 쓴다. 트랜잭션이 끝날 때까지
     * 같은 에이전트를 잠그려는 다른 요청은 잠금이 풀릴 때까지 기다린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.id = :id")
    Optional<Agent> findByIdForUpdate(@Param("id") Long id);

    /**
     * 번호로 에이전트의 id 만 읽는다. 행을 영속성 문맥에 올리지 않는다.
     *
     * <p>{@link #findByIdForUpdate} 로 잠금을 기다려 처음 읽어야 할 때 쓴다. 엔티티를 먼저 읽어 두면 잠금을 기다린 뒤에도
     * 그 앞에 읽은 값이 남아 다른 요청이 커밋한 값을 보지 못한다.
     */
    @Query("select a.id from Agent a where a.code = :code")
    Optional<Long> findIdByCode(@Param("code") String code);

    /** 도구와 공개 범위 변경은 경합하면 곧바로 거절해 오래 기다리지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select a from Agent a where a.code = :code")
    Optional<Agent> findByCodeForUpdate(@Param("code") String code);

    /**
     * 그 profile 을 이미 가리키는 에이전트가 있는가.
     *
     * <p>사람을 더할 때 profile 이름이 비는지 보는 자리가 둘이고 이것이 그 하나다. 두 사람이 같은
     * profile 을 쓰면 격리가 깨진다.
     */
    boolean existsByHermesProfile(String hermesProfile);

    /** 그 사용자가 주인인 지우지 않은 에이전트 수다. 사용자당 상한을 이 수로 센다. 커넥터 연결용 에이전트는 세지 않는다. */
    long countByOwnerUserIdAndDeletedAtIsNullAndConnectorManagedFalse(Long ownerUserId);
}
