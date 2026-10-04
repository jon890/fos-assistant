package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.TaskState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** 그 사용자의 작업 가운데 그 상태가 아닌 것의 수다. 보관하지 않은 작업 수를 셀 때 {@code ARCHIVED} 를 넘긴다. */
    long countByOwnerUserIdAndStateNot(Long ownerUserId, TaskState state);

    /** 그 사용자의 작업 한 줄이다. 남의 줄은 없는 줄과 같다. */
    Optional<Task> findByPublicIdAndOwnerUserId(UUID publicId, Long ownerUserId);

    /** 그 사용자의 작업 가운데 그 상태가 아닌 것을 만든 순서의 역순으로 읽는다. */
    List<Task> findByOwnerUserIdAndStateNotOrderByIdDesc(Long ownerUserId, TaskState state);
}
