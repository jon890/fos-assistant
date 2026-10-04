package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.TaskRun;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    /** 그 작업의 발화를 예정 시각의 역순으로 읽는다. 같은 시각이면 번호가 큰 쪽이 앞이다. 개수는 {@code pageable} 이 정한다. */
    List<TaskRun> findByTaskIdOrderByScheduledForDescIdDesc(Long taskId, Pageable pageable);
}
