package com.bifos.assistant.task.application;

import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.infra.TaskContents;
import com.bifos.assistant.task.infra.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 있는 예약 작업 한 줄의 제목과 지시문을 암호화하거나 평문으로 되돌린다(ADR-20261008 / data-encryption).
 *
 * <p>줄마다 새 트랜잭션에서 작업 줄을 쓰기 잠금으로 다시 읽고 조건을 다시 본다. 그 사이 사용자가 글을 고쳤거나 다른 차례가 먼저
 * 고쳤으면 건너뛴다. 암호화한 줄은 같은 트랜잭션에서 풀어 봐서 평문과 다르면 되돌린다. AAD 는 {@link TaskContents} 가 정한 그대로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class TaskPlaintextSealer {

    private final TaskRepository tasks;
    private final TaskContents contents;


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean seal(Long taskId) {
        Task task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.titleKeyId() != null) {
            return false;
        }
        String title = task.title();
        String instruction = task.instruction();
        contents.seal(task);
        tasks.flush();
        if (!contents.opensTo(task, title, instruction)) {
            throw new IllegalStateException("the sealed task does not open to the original");
        }
        return true;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean unseal(Long taskId) {
        Task task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.titleKeyId() == null || !contents.unseal(task)) {
            return false;
        }
        tasks.flush();
        return true;
    }
}
