package com.bifos.assistant.task.application;

import com.bifos.assistant.chat.application.ConversationTaskLabels;
import com.bifos.assistant.chat.application.model.TaskLabel;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.infra.TaskRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 대화 목록에 작업 이름을 준다(ADR-073). 지운 작업도 이름을 돌려준다. 그 작업이 만든 대화는 남기 때문이다.
 *
 * <p>트랜잭션을 열지 않아 조회 하나가 자기 트랜잭션으로 돈다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 번호가 비면
 * 읽지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TaskLabelSource implements ConversationTaskLabels {

    private final TaskRepository tasks;

    @Override
    public Map<Long, TaskLabel> labelsOf(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return tasks.findAllById(taskIds).stream()
                .collect(Collectors.toMap(Task::id, task -> new TaskLabel(task.publicId(), task.title())));
    }
}
