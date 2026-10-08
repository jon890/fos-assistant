package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProactiveLoopRunRepository extends JpaRepository<ProactiveLoopRun, Long> {

    Optional<ProactiveLoopRun> findBySourceCheckId(Long sourceCheckId);

    /** 하루 상한에 세는 시도 수다. 건너뛴 상태를 빼고 그 시각 뒤에 저장한 줄을 센다. */
    long countByUserIdAndStatusNotAndCreatedAtAfter(Long userId, LoopRunStatus status, Instant after);

    List<ProactiveLoopRun> findByStatus(LoopRunStatus status);

    List<ProactiveLoopRun> findBySourceCheckIdIn(Collection<Long> sourceCheckIds);
}
