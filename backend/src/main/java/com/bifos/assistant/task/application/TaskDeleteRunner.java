package com.bifos.assistant.task.application;

import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.infra.TaskRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자가 승인한 예약 작업 지우기를 한다(스펙 service-concepts REQ9). 화면의 지우기와 같이 줄을 {@code ARCHIVED} 로 남긴다. 지난 대화와
 * 실행 기록은 그대로다.
 *
 * <p>승인 줄을 잠근 트랜잭션 안에서 돈다. {@link TaskService} 의 예외는 그 트랜잭션을 되돌림으로 표시하므로 쓰지 않고, 없는 작업을
 * 결과로 돌려준다.
 */
@Component
@RequiredArgsConstructor
class TaskDeleteRunner implements ControlPlaneActionRunner {
    private final TaskRepository tasks;
    private final Clock clock;


    @Override
    public String toolName() {
        return ControlPlaneActions.TASK_DELETE;
    }


    @Override
    public ControlPlaneActionOutcome run(CurrentUser user, Map<String, String> args) {
        String name = args.getOrDefault("task", "");
        UUID taskId;
        try {
            taskId = UUID.fromString(args.getOrDefault("task_id", ""));
        } catch (IllegalArgumentException ex) {
            return ControlPlaneActionOutcome.failed("「" + name + "」 예약 작업을 찾지 못해 지우지 않았어요");
        }
        return tasks.findByPublicIdAndOwnerUserIdAndKindForUpdate(taskId, user.id(), TaskKind.TURN)
            .filter(task -> !task.archived())
            .map(task -> {
                task.archive(clock.instant());
                return ControlPlaneActionOutcome.done("「" + name + "」 예약 작업을 지웠어요");
            })
            .orElseGet(() -> ControlPlaneActionOutcome.failed("「" + name + "」 예약 작업이 이미 없어 지우지 않았어요"));
    }
}
