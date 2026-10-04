package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.TaskTrigger;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskTriggerRepository extends JpaRepository<TaskTrigger, Long> {

    /** 그 작업의 시각이다. 작업 하나에 하나다. */
    Optional<TaskTrigger> findByTaskId(Long taskId);

    /** 여러 작업의 시각을 한 번에 읽는다. */
    List<TaskTrigger> findByTaskIdIn(Collection<Long> taskIds);
}
