package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    /** 그 작업의 발화를 예정 시각의 역순으로 읽는다. 같은 시각이면 번호가 큰 쪽이 앞이다. 개수는 {@code pageable} 이 정한다. */
    List<TaskRun> findByTaskIdOrderByScheduledForDescIdDesc(Long taskId, Pageable pageable);

    /** 그 상태의 발화를 예정 시각 순으로 읽는다. 같은 시각이면 번호가 작은 쪽이 앞이다. */
    List<TaskRun> findByStatusOrderByScheduledForAscIdAsc(TaskRunStatus status);

    /** 그 trigger 의 그 예정 시각 줄이 이미 있는가. 유일 제약에 닿기 전에 본다. */
    boolean existsByTriggerIdAndScheduledFor(Long triggerId, Instant scheduledFor);

    /**
     * 그 사용자가 {@code after} 뒤에 만든 발화 가운데 그 상태가 아닌 것의 수다. 하루 발화 수를 셀 때 {@code SKIPPED} 를
     * 넘긴다.
     */
    long countByOwnerUserIdAndCreatedAtAfterAndStatusNot(Long ownerUserId, Instant after, TaskRunStatus status);

    /** 상태를 바꾸기 전에 줄을 잠그고 다시 읽는다. 두 tick 이나 끝난 turn 과 기동 정리가 같은 줄을 함께 바꾸지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TaskRun r where r.id = :id")
    Optional<TaskRun> findByIdForUpdate(@Param("id") Long id);
}
