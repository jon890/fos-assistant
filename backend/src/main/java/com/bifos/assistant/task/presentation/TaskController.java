package com.bifos.assistant.task.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.presentation.TaskDtos.TaskRequest;
import com.bifos.assistant.task.presentation.TaskDtos.TaskRunView;
import com.bifos.assistant.task.presentation.TaskDtos.TaskView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사용자 자신의 예약 작업을 다루는 경로다(ADR-076). 요청 본문이 주인을 정하지 못한다.
 *
 * <p>계약은 {@code docs/backend/task.md} 의 「API」 가 갖는다.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final CurrentUserProvider currentUser;
    private final TaskService tasks;

    /** 보관하지 않은 작업을 만든 순서의 역순으로 돌려준다. */
    @GetMapping
    public List<TaskView> list() {
        return tasks.list(currentUser.require()).stream().map(TaskView::from).toList();
    }

    @PostMapping
    public TaskView create(@Valid @RequestBody TaskRequest request) {
        return TaskView.from(tasks.create(currentUser.require(), request.toInput()));
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable UUID taskId) {
        return TaskView.from(tasks.get(currentUser.require(), taskId));
    }

    @PutMapping("/{taskId}")
    public TaskView update(@PathVariable UUID taskId, @Valid @RequestBody TaskRequest request) {
        return TaskView.from(tasks.update(currentUser.require(), taskId, request.toInput()));
    }

    @PostMapping("/{taskId}/pause")
    public TaskView pause(@PathVariable UUID taskId) {
        return TaskView.from(tasks.pause(currentUser.require(), taskId));
    }

    @PostMapping("/{taskId}/resume")
    public TaskView resume(@PathVariable UUID taskId) {
        return TaskView.from(tasks.resume(currentUser.require(), taskId));
    }

    /** 지운다. 줄은 보관한 채 남는다. */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> archive(@PathVariable UUID taskId) {
        tasks.archive(currentUser.require(), taskId);
        return ResponseEntity.noContent().build();
    }

    /** 발화 기록을 예정 시각의 역순으로 돌려준다. */
    @GetMapping("/{taskId}/runs")
    public List<TaskRunView> runs(@PathVariable UUID taskId, @RequestParam(defaultValue = "20") int limit) {
        return tasks.runs(currentUser.require(), taskId, limit).stream()
                .map(TaskRunView::from)
                .toList();
    }
}
