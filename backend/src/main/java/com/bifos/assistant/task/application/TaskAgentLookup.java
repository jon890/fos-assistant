package com.bifos.assistant.task.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.task.application.dto.TaskTarget;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.TaskKind;
import com.bifos.assistant.task.domain.type.TaskState;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.task.infra.TaskRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트의 예약 작업 도구가 대상을 찾고 결과 칸을 만든다(서비스 개념 설계의 「PR2 예약 작업 도구」).
 *
 * <p>요청자의 지우지 않은 일반 예약 작업만 본다. 매일 깨우기와 남의 작업은 없는 것과 같다. 제목과 지시문은 암호문이라 읽는 트랜잭션
 * 안에서 결과 칸까지 만든다.
 */
@Component
@RequiredArgsConstructor
class TaskAgentLookup {
    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final TaskRepository tasks;


    /** 번호가 맞으면 그 작업, 아니면 이름이 똑같은(앞뒤 공백과 대소문자 무시) 작업이다. */
    @Transactional(readOnly = true)
    TaskTarget find(CurrentUser user, String ref) {
        List<Task> owned = owned(user);
        String key = ref.strip();
        if (UUID_TEXT.matcher(key).matches()) {
            UUID id = UUID.fromString(key);
            List<Task> byId = owned.stream().filter(task -> task.publicId().equals(id)).toList();
            if (!byId.isEmpty()) {
                return new TaskTarget(id, List.of());
            }
        }
        List<Task> byTitle = owned.stream()
            .filter(task -> task.title().strip().toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT)))
            .toList();
        if (byTitle.size() == 1) {
            return new TaskTarget(byTitle.getFirst().publicId(), List.of());
        }
        return new TaskTarget(null, byTitle.stream().map(TaskAgentLookup::candidate).toList());
    }


    /**
     * 이름(앞뒤 공백과 대소문자 무시)과 시각이 같은 작업이다. 에이전트가 같은 만들기를 다시 불러도 두 번 만들지 않으려고 본다.
     *
     * @param fireAt {@code 2026-11-21T07:00} 꼴. 작업의 시간대로 견준다
     */
    @Transactional(readOnly = true)
    Optional<UUID> sameTask(CurrentUser user, String title, String cron, String fireAt) {
        String key = title.strip().toLowerCase(Locale.ROOT);
        return owned(user).stream()
            .filter(task -> task.title().strip().toLowerCase(Locale.ROOT).equals(key))
            .filter(task -> cron != null
                ? task.type() == TriggerType.CRON && cron.strip().equals(task.cronExpr())
                : task.type() == TriggerType.ONCE && task.fireAt() != null
                    && LocalDateTime.ofInstant(task.fireAt(), task.zone()).toString().startsWith(fireAt.strip()))
            .map(Task::publicId)
            .findFirst();
    }


    /** 목록 도구의 항목이다. */
    @Transactional(readOnly = true)
    List<Map<String, Object>> list(CurrentUser user) {
        return owned(user).stream().map(TaskAgentLookup::summary).toList();
    }


    /** 바꾼 뒤 결과에 실을 칸이다. 없으면 null 이다. */
    @Transactional(readOnly = true)
    Map<String, Object> summary(CurrentUser user, UUID taskId) {
        return tasks.findByPublicIdAndOwnerUserIdAndKind(taskId, user.id(), TaskKind.TURN)
            .filter(task -> !task.archived())
            .map(TaskAgentLookup::summary)
            .orElse(null);
    }


    private List<Task> owned(CurrentUser user) {
        return tasks.findByOwnerUserIdAndKindAndStateNotOrderByIdDesc(user.id(), TaskKind.TURN, TaskState.ARCHIVED);
    }


    static Map<String, Object> summary(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.publicId().toString());
        item.put("title", task.title());
        item.put("state", task.state().name());
        item.put("schedule", task.type() == TriggerType.CRON
            ? "cron " + task.cronExpr() + " (" + task.timeZone() + ")"
            : "once " + local(task, task.fireAt()));
        item.put("next_run", local(task, task.nextFireAt()));
        item.put("notify", task.notifyPolicy().name().toLowerCase(Locale.ROOT));
        item.put("link", link(task.publicId()));
        return item;
    }


    private static Map<String, Object> candidate(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.publicId().toString());
        item.put("title", task.title());
        item.put("next_run", local(task, task.nextFireAt()));
        return item;
    }


    /** 작업의 시간대로 적은 시각이다. 없으면 null 이다. */
    static String local(Task task, Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, task.zone()).format(LOCAL) + " (" + task.timeZone() + ")";
    }


    static String link(UUID taskId) {
        return "/tasks/" + taskId;
    }
}
