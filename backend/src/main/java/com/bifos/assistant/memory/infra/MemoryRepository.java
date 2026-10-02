package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.Memory;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryRepository extends JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory> {

    Optional<Memory> findByProposalDedupKey(String proposalDedupKey);

    /**
     * 항목 한 줄을 쓰기 잠금으로 읽는다.
     *
     * <p>고치거나 지울 때 그 전의 값을 판으로 남기는데, 두 요청이 같은 판 번호를 읽으면 같은 판을 두 번 남기려 한다.
     * 한 번에 하나씩 돌려 둘째가 첫째가 올린 판 번호를 읽게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Memory m where m.id = :id")
    Optional<Memory> findByIdForUpdate(@Param("id") Long id);
}
