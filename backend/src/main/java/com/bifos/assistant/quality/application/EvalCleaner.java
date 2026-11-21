package com.bifos.assistant.quality.application;

import com.bifos.assistant.chat.artifact.application.ArtifactVaultFinder;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.EvalResult.ItemResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.dto.TaskDetail;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.user.domain.AppUser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 평가 문항 하나의 대화로 평가 전용 사용자에게 생긴 것을 지운다(docs/features/agent-quality/design.md 의 「비우기」).
 *
 * <p>시각이 아니라 문항의 대화 번호로 찾는다. 함께 도는 배포 뒤 확인은 대화가 달라 그것이 만든 것을 지우지 않는다. 하나가 실패해도 나머지를 계속 지우고
 * 경고 로그만 남긴다. 부르는 쪽은 결과를 보고 「비우기 끝남」 을 적을지 정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EvalCleaner {

    private final TaskService tasks;
    private final FollowUpService followUps;
    private final ConnectorActionRepository connectorActions;
    private final ConnectorActionService actionService;
    private final ArtifactVaultFinder vaultFinder;
    private final ArtifactVaultService vault;
    private final DeployCheckCleaner deployCheckCleaner;
    private final EvalRuns evalRuns;
    private final QualityRunRepository runs;
    private final EvaluationUserService evaluationUsers;
    private final LiveProperties<QualityProperties> properties;
    private final Clock clock;


    /**
     * 문항 대화가 만든 예약 작업, 기억, 할 일, 보관본을 지우고 기다리는 승인 줄을 거절로 닫는다. 대화 자체는 지우지 않는다.
     *
     * @param questionStartedAt 문항을 시작한 시각. 위임 자식이 만들어 대화 칸이 빈 예약 작업을 이 시각 뒤로 찾는다
     * @return 모두 지웠으면 참
     */
    boolean cleanQuestion(CurrentUser evaluation, Long conversationId, Instant questionStartedAt) {
        boolean all = archiveTasks(evaluation, conversationId, questionStartedAt);
        all &= rejectPending(evaluation, conversationId);
        all &= deployCheckCleaner.forgetCaptured(evaluation, conversationId);
        all &= deleteFollowUps(evaluation, conversationId);
        all &= deleteVaultItems(evaluation, conversationId);
        return all;
    }


    /** 그 대화의 기다리는 승인 줄을 거절로 닫는다. 판정 직후 대화를 지우기 전에도 부른다. 줄은 기록으로 남는다. */
    boolean rejectPending(CurrentUser evaluation, Long conversationId) {
        boolean all = true;
        for (ConnectorAction action : connectorActions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(
            conversationId, evaluation.id(), ActionStatus.PENDING)) {
            try {
                actionService.reject(evaluation, action.publicId());
            } catch (RuntimeException ex) {
                all = false;
                log.warn("평가 문항의 승인 줄을 거절하지 못했다 actionId={}", action.publicId(), ex);
            }
        }
        return all;
    }


    /**
     * 그 대화의 예약 작업을 보관 처리한다. 루트 실행이 만든 작업은 대화 칸이 그 대화다. 위임 자식이 만든 작업은 대화 칸이 비므로, 문항 시작 뒤 생긴 대화 칸 빈 작업
     * 가운데 배포 뒤 확인의 고정 지시문이 아닌 것을 함께 보관 처리한다.
     */
    private boolean archiveTasks(CurrentUser evaluation, Long conversationId, Instant questionStartedAt) {
        boolean all = true;
        for (TaskDetail detail : tasks.list(evaluation)) {
            Task task = detail.task();
            boolean ownConversation = Objects.equals(task.conversationId(), conversationId);
            boolean detachedChild = task.conversationId() == null
                && questionStartedAt != null
                && task.createdAt() != null
                && !task.createdAt().isBefore(questionStartedAt)
                && !DeployCheckItems.TASK_INSTRUCTION.equals(task.instruction());
            if (!ownConversation && !detachedChild) {
                continue;
            }
            try {
                tasks.archive(evaluation, task.publicId());
            } catch (RuntimeException ex) {
                all = false;
                log.warn("평가 문항이 만든 예약 작업을 보관 처리하지 못했다 taskId={}", task.publicId(), ex);
            }
        }
        return all;
    }


    private boolean deleteFollowUps(CurrentUser evaluation, Long conversationId) {
        try {
            followUps.deleteForConversation(evaluation, conversationId);
            return true;
        } catch (RuntimeException ex) {
            log.warn("평가 문항이 만든 할 일을 지우지 못했다 conversationId={}", conversationId, ex);
            return false;
        }
    }


    private boolean deleteVaultItems(CurrentUser evaluation, Long conversationId) {
        try {
            for (Long id : vaultFinder.putIdsOf(evaluation.id(), conversationId)) {
                vault.delete(evaluation.id(), id);
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("평가 문항이 넣은 보관본을 지우지 못했다 conversationId={}", conversationId, ex);
            return false;
        }
    }


    /**
     * 비우기가 끝나지 않은 끝난 평가 줄의 문항 대화를 다시 비운다. 끊긴 평가와 비우기에 실패한 평가가 남긴 예약 작업이 발화하지 않게 한다. 평가 시작, 재기동,
     * 하루 정리가 부른다.
     */
    void recleanFinished() {
        for (Long runId : evalRuns.uncleaned()) {
            try {
                QualityRun run = runs.findById(runId).orElseThrow();
                Optional<AppUser> evaluation = evaluationUsers.find(run.groupId());
                if (evaluation.isEmpty()) {
                    // 평가 사용자를 지울 때 그 사용자의 것도 함께 지워졌다.
                    evalRuns.markCleaned(runId);
                    continue;
                }
                CurrentUser user = EvaluationUserService.asCurrentUser(evaluation.get());
                boolean all = true;
                for (ItemResult item : run.evalResult().items()) {
                    if (item.conversationId() != null) {
                        all &= cleanQuestion(user, item.conversationId(), item.startedAt());
                    }
                }
                if (all) {
                    evalRuns.markCleaned(runId);
                }
            } catch (RuntimeException ex) {
                log.warn("끝난 평가를 다시 비우지 못했다 runId={}", runId, ex);
            }
        }
    }


    /** 서버가 다시 뜨면 끊긴 평가({@link EvalRuns#interruptRunning} 이 먼저 끝낸다)가 남긴 것을 바로 비운다. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    void afterRestart() {
        recleanFinished();
    }


    /** 하루 한 번 비우기가 끝나지 않은 평가를 다시 비우고, 보관 기간이 지난 실패·남긴 문항의 대화를 지운 뒤 링크를 비운다. 줄과 숫자는 남는다. */
    @Scheduled(cron = "${assistant.quality.cleanup-cron}")
    void sweep() {
        recleanFinished();
        Instant before = clock.instant().minus(properties.current().linkRetention());
        for (QualityRunStatus status : List.of(QualityRunStatus.PASSED, QualityRunStatus.FAILED)) {
            for (QualityRun run : runs.findByKindAndStatusAndStartedAtBefore(QualityRunKind.EVAL, status, before)) {
                List<UUID> conversations = run.evalResult().items().stream()
                    .map(ItemResult::conversationPublicId)
                    .filter(Objects::nonNull)
                    .toList();
                if (conversations.isEmpty()) {
                    continue;
                }
                Optional<AppUser> evaluation = evaluationUsers.find(run.groupId());
                Set<UUID> removed = new HashSet<>();
                if (evaluation.isPresent()) {
                    CurrentUser user = EvaluationUserService.asCurrentUser(evaluation.get());
                    conversations.stream().filter(publicId -> deployCheckCleaner.dropConversation(user, publicId)).forEach(removed::add);
                } else {
                    removed.addAll(conversations);
                }
                evalRuns.dropLinks(run.id(), removed);
            }
        }
    }
}
