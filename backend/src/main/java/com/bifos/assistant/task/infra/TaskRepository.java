package com.bifos.assistant.task.infra;

import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TaskKind;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** 그 사용자의 해당 종류 작업 가운데 그 상태가 아닌 것의 수다. */
    long countByOwnerUserIdAndKindAndStateNot(Long ownerUserId, TaskKind kind, TaskState state);

    /** 그 사용자의 작업 한 줄이다. 남의 줄은 없는 줄과 같다. */
    Optional<Task> findByPublicIdAndOwnerUserIdAndKind(UUID publicId, Long ownerUserId, TaskKind kind);

    /** 상태를 바꾸기 전에 작업 줄을 잠그고 다시 읽는다. 잠그는 순서는 {@code task_run} 다음 {@code task} 다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    /** 상태를 바꾸기 전에 주인의 작업 줄을 잠그고 다시 읽는다. 잠그는 순서는 {@code task_run} 다음 {@code task} 다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.publicId = :publicId and t.ownerUserId = :ownerUserId and t.kind = :kind")
    Optional<Task> findByPublicIdAndOwnerUserIdAndKindForUpdate(
            @Param("publicId") UUID publicId, @Param("ownerUserId") Long ownerUserId, @Param("kind") TaskKind kind);

    /** 그 사용자의 작업 가운데 그 상태가 아닌 것을 만든 순서의 역순으로 읽는다. */
    List<Task> findByOwnerUserIdAndKindAndStateNotOrderByIdDesc(Long ownerUserId, TaskKind kind, TaskState state);

    Optional<Task> findByOwnerUserIdAndAgentIdAndKind(Long ownerUserId, Long agentId, TaskKind kind);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.ownerUserId = :ownerUserId and t.agentId = :agentId and t.kind = :kind")
    Optional<Task> findByOwnerUserIdAndAgentIdAndKindForUpdate(
            @Param("ownerUserId") Long ownerUserId, @Param("agentId") Long agentId, @Param("kind") TaskKind kind);

    /**
     * {@code SINGLE} 작업이 결과를 쌓을 대화와 고친 시각만 적는다. 잠그지 않고 읽은 엔티티를 저장하면 그 사이에 사용자가 커밋한 멈춤과
     * 수정을 읽은 때의 값으로 되돌린다. {@code at} 은 부르는 쪽이 마이크로초로 자른다.
     */
    @Modifying
    @Query("update Task t set t.conversationId = :conversationId, t.updatedAt = :at where t.id = :id")
    int useConversation(@Param("id") Long id, @Param("conversationId") Long conversationId, @Param("at") Instant at);
}
