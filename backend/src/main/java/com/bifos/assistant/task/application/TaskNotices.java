package com.bifos.assistant.task.application;

import com.bifos.assistant.chat.application.ConversationPublicIdLookup;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 끝난 발화를 작업 주인에게 알린다.
 *
 * <p>무엇을 언제 알리는지는 {@code backend/docs/flow.md} 의 「알림(예약 작업)」 이, 제목과 까닭 한 줄과 누르면 가는 곳은 이 클래스가 갖는다.
 * 작업의 알림 설정을 지킨다. 알림은 발화의 상태를 바꾸는 트랜잭션 안에서 만들어야 하므로 부르는 쪽 트랜잭션 안에서만 부른다.
 */
@Component
@RequiredArgsConstructor
public class TaskNotices {

    /** 알릴 까닭과 그 한 줄이다. 화면 문구라 오류 코드와 내부 원인은 넣지 않는다. 여기 없는 까닭은 알리지 않는다. */
    private static final Map<TaskRunReason, String> REASON_LINES = Map.of(
            TaskRunReason.BUSY, "다른 대화가 오래 돌고 있어 시작하지 못했어요",
            TaskRunReason.DAILY_LIMIT, "하루 실행 횟수를 다 썼어요",
            TaskRunReason.AGENT_UNAVAILABLE, "에이전트를 쓸 수 없어요. 작업의 에이전트를 확인해 주세요",
            TaskRunReason.FAILED, "실행 중에 문제가 생겼어요",
            TaskRunReason.INTERRUPTED, "서버가 다시 시작돼 실행이 끊겼어요");

    private final NotificationService notifications;
    private final ConversationPublicIdLookup conversations;

    /**
     * 그 발화의 지금 상태에 맞는 알림을 만든다. 알릴 것이 없으면 아무것도 하지 않는다.
     *
     * @param task 그 발화의 작업
     * @param run 상태를 막 바꾼 발화
     */
    public void announce(Task task, TaskRun run) {
        if (task.kind() == TaskKind.CHECK) {
            return;
        }
        NotifyPolicy policy = task.notifyPolicy();
        if (policy == NotifyPolicy.NEVER) {
            return;
        }
        switch (run.status()) {
            case SUCCEEDED -> {
                // 「보고할 것 없음」 으로 끝낸 발화는 알림 설정과 관계없이 알리지 않는다.
                if (policy == NotifyPolicy.ALWAYS && run.reason() != TaskRunReason.NOTHING_TO_REPORT) {
                    notifications.notify(
                            task.ownerUserId(),
                            NotificationKind.TASK_SUCCEEDED,
                            "「" + task.title() + "」 실행을 마쳤어요",
                            "",
                            conversationOrTask(task, run));
                }
            }
            case FAILED -> {
                String line = REASON_LINES.get(run.reason());
                if (line != null) {
                    notifications.notify(
                            task.ownerUserId(),
                            NotificationKind.TASK_FAILED,
                            "「" + task.title() + "」 실행이 실패했어요",
                            line,
                            conversationOrTask(task, run));
                }
            }
            case SKIPPED -> {
                String line = REASON_LINES.get(run.reason());
                if (line != null) {
                    notifications.notify(
                            task.ownerUserId(),
                            NotificationKind.TASK_SKIPPED,
                            "「" + task.title() + "」 실행을 건너뛰었어요",
                            line,
                            taskTarget(task));
                }
            }
            default -> {
                // 기다리는 줄, 도는 줄, 사용자가 중지한 줄은 알리지 않는다.
            }
        }
    }

    /** 그 발화의 대화가 있으면 그 대화, 없으면 그 작업이다. */
    private NotificationTarget conversationOrTask(Task task, TaskRun run) {
        if (run.conversationId() == null) {
            return taskTarget(task);
        }
        UUID publicId = conversations.publicIdsOf(List.of(run.conversationId())).get(run.conversationId());
        return publicId == null
                ? taskTarget(task)
                : new NotificationTarget(NotificationTargetType.CONVERSATION, publicId);
    }

    private static NotificationTarget taskTarget(Task task) {
        return new NotificationTarget(NotificationTargetType.TASK, task.publicId());
    }
}
