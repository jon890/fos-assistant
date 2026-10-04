package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.TaskState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskTriggerRepository extends JpaRepository<TaskTrigger, Long> {

    /** 그 작업의 시각이다. 작업 하나에 하나다. */
    Optional<TaskTrigger> findByTaskId(Long taskId);

    /** 여러 작업의 시각을 한 번에 읽는다. */
    List<TaskTrigger> findByTaskIdIn(Collection<Long> taskIds);

    /**
     * 다음 예정 시각이 {@code now} 이하이고 작업이 그 상태인 시각의 번호를 예정 시각 순으로 읽는다. 잠그지 않는다. 발화기가
     * 하나씩 {@link #findByIdForUpdate} 로 다시 읽는다.
     */
    @Query("select t.id from TaskTrigger t, Task k"
            + " where k.id = t.taskId and k.state = :state and t.nextFireAt <= :now"
            + " order by t.nextFireAt, t.id")
    List<Long> findDueIds(@Param("now") Instant now, @Param("state") TaskState state);

    /** 발화하기 전에 줄을 잠그고 다시 읽는다. 두 tick 이 같은 예정 시각을 함께 처리하지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskTrigger t where t.id = :id")
    Optional<TaskTrigger> findByIdForUpdate(@Param("id") Long id);
}
