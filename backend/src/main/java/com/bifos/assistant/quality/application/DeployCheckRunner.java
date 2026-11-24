package com.bifos.assistant.quality.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.application.TurnCancellation;
import com.bifos.assistant.mcp.application.McpConnectionLiveness;
import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.FeatureVerdict;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.type.CheckReason;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.task.application.dto.ScheduleInput;
import com.bifos.assistant.task.application.dto.TaskDetail;
import com.bifos.assistant.task.application.dto.TaskInput;
import com.bifos.assistant.task.application.dto.TaskRunDetail;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.domain.type.TriggerType;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 배포 뒤 확인 한 번을 끝까지 돌린다(docs/features/agent-quality/design.md 의 「확인 한 번의 흐름」).
 *
 * <p>기능은 차례로 돈다. 평가 전용 사용자에게도 사용자 실행 한도가 걸리고 위임 확인은 자식 실행 자리를 하나 더 쓰므로, 겹쳐 돌면
 * 한도에 닿는다. 문항은 사람이 보낸 질문과 같은 {@link ChatService#send} 길로 보낸다.
 *
 * <p>{@code send} 는 끝날 때까지 막히고, turn 취소 표시는 Hermes run 을 멈추지 않는다. 그래서 문항은 별도 작업에서 보내고 이
 * 스레드는 끝남 조건을 {@code poll-interval} 마다 본다. 기능 마감이 지나면 도는 turn 과 위임 자식을 멈추고 {@code TIMED_OUT} 으로
 * 적는다. 대화에 도는 turn 이 없어진 뒤 다음 기능으로 간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DeployCheckRunner {

    /** 예약 작업 발화가 끝난 상태다. */
    private static final Set<TaskRunStatus> TASK_RUN_ENDED = Set.of(
        TaskRunStatus.SUCCEEDED, TaskRunStatus.FAILED, TaskRunStatus.CANCELLED, TaskRunStatus.SKIPPED);

    private static final ZoneId TASK_ZONE = ZoneId.of("Asia/Seoul");

    private final DeployCheckRuns runs;
    private final DeployCheckJudge judge;
    private final DeployCheckObserver observer;
    private final DeployCheckCleaner cleaner;
    private final EvaluationUserService evaluationUsers;
    private final AppUserRepository users;
    private final ChatService chat;
    private final AttachmentService attachments;
    private final TaskService tasks;
    private final TurnCancellation turns;
    private final TurnDriver driver;
    private final McpConnectionLiveness liveness;
    private final BackgroundTasks background;
    private final LiveProperties<QualityProperties> properties;
    private final Clock clock;


    /** 확인 줄 하나를 요청 밖에서 돌린다. 줄은 {@link DeployCheckRuns#open} 이 먼저 만든다. */
    void launch(Long runId, Long evaluationUserId) {
        background.start("deploy-check-" + runId, () -> run(runId, evaluationUserId));
    }


    /** 기능을 차례로 돌리고 줄을 끝낸다. 도중의 예외는 남은 기능을 {@code RUN_FAILED} 로 적고 끝낸다. */
    void run(Long runId, Long evaluationUserId) {
        CurrentUser evaluation = null;
        QualityFeature current = QualityFeature.values()[0];
        try {
            evaluation = EvaluationUserService.asCurrentUser(users.findById(evaluationUserId).orElseThrow());
            for (QualityFeature feature : QualityFeature.values()) {
                current = feature;
                Outcome outcome = runFeature(feature, evaluation);
                runs.record(runId, outcome.result(), outcome.modelCalls());
            }
            current = null;
        } catch (RuntimeException ex) {
            log.error("배포 뒤 확인이 도중에 멈췄다 runId={} feature={}", runId, current, ex);
            recordRest(runId, current);
        } finally {
            runs.finish(runId);
        }
    }


    /** 멈춘 기능부터 끝까지를 실패로 적는다. 적다 실패해도 줄은 끝낸다. */
    private void recordRest(Long runId, QualityFeature from) {
        if (from == null) {
            return;
        }
        try {
            for (QualityFeature feature : QualityFeature.values()) {
                if (feature.ordinal() >= from.ordinal()) {
                    runs.record(runId, new FeatureResult(
                        feature, FeatureStatus.FAILED, CheckReason.RUN_FAILED.name(), 0, null, List.of()), 0);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("멈춘 배포 뒤 확인의 남은 기능을 적지 못했다 runId={}", runId, ex);
        }
    }


    /** 기능 하나를 돌리고 판정한다. 통과면 만든 것을 바로 지우고, 아니면 대화와 실행 번호를 결과에 남긴다. */
    private Outcome runFeature(QualityFeature feature, CurrentUser evaluation) {
        long startedNanos = System.nanoTime();
        Instant deadline = clock.instant().plus(properties.current().featureTimeout());
        Attempt attempt = new Attempt();
        FeatureVerdict verdict;
        try {
            CheckObservation observation = feature == QualityFeature.TASK_FIRE
                ? fireTask(evaluation, deadline, attempt)
                : converse(feature, evaluation, deadline, attempt);
            verdict = attempt.busy ? FeatureVerdict.failed(CheckReason.BUSY)
                : attempt.rejected ? FeatureVerdict.failed(CheckReason.RUN_FAILED)
                    : judge.judge(feature, observation);
        } catch (RuntimeException ex) {
            log.warn("배포 뒤 확인의 기능이 예외로 끝났다 feature={}", feature, ex);
            verdict = FeatureVerdict.failed(CheckReason.RUN_FAILED);
        }
        if (verdict.status() != FeatureStatus.PASSED && !controlPlaneConnected(evaluation)) {
            // 도구를 받지 못한 채 돈 실패는 기능의 회귀가 아니다. 까닭을 환경 오류로 바꿔 품질 실패와 섞이지 않게 한다.
            log.warn("평가 비서의 Control Plane MCP 연결이 끊긴 채 기능이 실패했다 feature={}", feature);
            verdict = FeatureVerdict.failed(CheckReason.ENVIRONMENT);
        }
        if (attempt.conversationId != null) {
            // 문항이 돌다 기억 도구로 남긴 것은 통과와 상관없이 되돌린다. 대화 번호로 찾아 함께 도는 평가의 기억은 건드리지 않는다.
            // 아래 조회가 실패해도 건너뛰지 않게 먼저 부른다.
            cleaner.forgetCaptured(evaluation, attempt.conversationId);
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        List<Long> executionIds = attempt.conversationId == null
            ? List.of()
            : observer.roots(attempt.conversationId).stream().map(AgentExecution::id).toList();
        int modelCalls = attempt.conversationId == null ? 0 : observer.modelCalls(attempt.conversationId);
        boolean passed = verdict.status() == FeatureStatus.PASSED;
        if (passed && attempt.conversationId != null) {
            cleaner.dropConversation(evaluation, attempt.conversationId);
        }
        FeatureResult result = passed
            ? new FeatureResult(feature, verdict.status(), null, elapsedMs, null, List.of())
            : new FeatureResult(feature, verdict.status(), verdict.reasonCode(), elapsedMs, attempt.conversationPublicId, executionIds);
        return new Outcome(result, modelCalls);
    }


    /** 평가 비서 profile 의 Control Plane MCP 연결이 살아 있는가. 기본 비서가 없으면 연결도 없다고 본다. */
    private boolean controlPlaneConnected(CurrentUser evaluation) {
        return evaluationUsers.primaryAgent(evaluation).map(Agent::hermesProfile).filter(liveness::connected).isPresent();
    }


    /** 기능 하나의 결과와 그 기능의 모델 호출 수다. */
    private record Outcome(FeatureResult result, int modelCalls) {
    }

    /** 기능 하나가 도는 동안 모으는 값이다. 예외로 끝나도 만든 대화를 결과에 남기려고 밖에 둔다. */
    private static final class Attempt {
        private Long conversationId;
        private UUID conversationPublicId;
        private boolean busy;
        /** 문항이 실행 줄을 만들기 전에 {@code BUSY} 가 아닌 까닭으로 거절됐다. 판정은 기능 규칙 대신 {@code RUN_FAILED} 다. */
        private boolean rejected;
        private boolean timedOut;
    }


    /** 평가 대화를 하나 열고 기능의 문항을 보낸 뒤 그 대화를 살핀다. */
    private CheckObservation converse(QualityFeature feature, CurrentUser evaluation, Instant deadline, Attempt attempt) {
        Agent primary = evaluationUsers.primaryAgent(evaluation)
            .orElseThrow(() -> new IllegalStateException("evaluation user has no primary agent"));
        Conversation conversation = chat.startEmpty(evaluation, primary.code());
        attempt.conversationId = conversation.id();
        attempt.conversationPublicId = conversation.publicId();
        switch (feature) {
            case SEARCH -> send(evaluation, attempt, DeployCheckItems.SEARCH_PROMPT, List.of(), deadline);
            case DELEGATION -> delegate(evaluation, attempt, deadline);
            case FILE_PUT -> send(evaluation, attempt, DeployCheckItems.FILE_PUT_PROMPT, List.of(), deadline);
            case IMAGE -> send(evaluation, attempt, DeployCheckItems.IMAGE_PROMPT, List.of(), deadline);
            case MEMORY_READ -> send(evaluation, attempt, DeployCheckItems.MEMORY_PROMPT, List.of(), deadline);
            case TOOL_DISCOVERY -> send(evaluation, attempt, DeployCheckItems.TOOL_DISCOVERY_PROMPT, List.of(), deadline);
            case PHOTO_INSPECT -> {
                ChatAttachment photo = uploadPhoto(evaluation, conversation.id());
                send(evaluation, attempt, DeployCheckItems.PHOTO_UPLOAD_PROMPT, List.of(photo.id()), deadline);
                send(evaluation, attempt, DeployCheckItems.PHOTO_ASK_PROMPT, List.of(), deadline);
            }
            case TASK_FIRE -> throw new IllegalArgumentException("task fire does not converse");
        }
        driver.settle(evaluation, attempt.conversationId);
        return observer.observe(evaluation, attempt.conversationId, attempt.timedOut);
    }


    /** 위임 문항을 보내고, 위임 자식이 있으면 결과가 대화에 전해질 때까지 기다린다. 자식이 없으면 바로 끝낸다. */
    private void delegate(CurrentUser evaluation, Attempt attempt, Instant deadline) {
        Agent target = evaluationUsers.delegateAgent(evaluation)
            .orElseThrow(() -> new IllegalStateException("evaluation user has no delegate agent"));
        send(evaluation, attempt, DeployCheckItems.DELEGATION_PROMPT.formatted(target.code()), List.of(), deadline);
        if (attempt.timedOut || attempt.busy || attempt.rejected) {
            return;
        }
        Long conversationId = attempt.conversationId;
        boolean settled = driver.waitUntil(deadline,
            () -> observer.delegationSettled(conversationId) && !turns.markOf(conversationId).running());
        if (!settled) {
            attempt.timedOut = true;
            driver.stopConversation(evaluation, conversationId);
        }
    }


    /** 문항 하나를 보내고 끝나기를 기다린다. 앞 문항이 시간 초과나 거절로 끝났으면 보내지 않는다. */
    private void send(CurrentUser evaluation, Attempt attempt, String text, List<Long> attachmentIds, Instant deadline) {
        if (attempt.timedOut || attempt.busy || attempt.rejected) {
            return;
        }
        switch (driver.send(evaluation, attempt.conversationId, text, attachmentIds, deadline)) {
            case TIMED_OUT -> attempt.timedOut = true;
            case BUSY -> attempt.busy = true;
            case REJECTED -> attempt.rejected = true;
            case DONE -> {
                // 실행 실패는 실행 줄에 남아 판정이 읽는다.
            }
        }
    }


    /**
     * 예약 작업 하나를 「지금 뒤 1분 남짓」 으로 만들어 발화기에 맡기고 발화가 끝나기를 기다린다. 결과와 상관없이 끝날 때 작업을
     * 보관 처리해, 시간 초과로 끝나도 뒤 기능 도중에 발화하지 않게 한다.
     */
    private CheckObservation fireTask(CurrentUser evaluation, Instant deadline, Attempt attempt) {
        Agent primary = evaluationUsers.primaryAgent(evaluation)
            .orElseThrow(() -> new IllegalStateException("evaluation user has no primary agent"));
        // ONCE 는 지금보다 뒤여야 한다. 분 단위로 자른 뒤 2분을 더해 늘 1분 넘게 뒤가 되게 한다.
        LocalDateTime fireAt = LocalDateTime.now(clock.withZone(TASK_ZONE)).truncatedTo(ChronoUnit.MINUTES).plusMinutes(2);
        TaskDetail created = tasks.create(evaluation, new TaskInput(
            DeployCheckItems.TASK_TITLE,
            DeployCheckItems.TASK_INSTRUCTION,
            new ScheduleInput(TriggerType.ONCE, null, fireAt, TASK_ZONE.getId()),
            ConversationMode.NEW_PER_RUN,
            null,
            NotifyPolicy.NEVER,
            null));
        UUID taskId = created.task().publicId();
        Optional<TaskRunDetail> ended = Optional.empty();
        try {
            boolean done = driver.waitUntil(deadline, () -> latestRun(evaluation, taskId)
                .filter(run -> TASK_RUN_ENDED.contains(run.run().status()))
                .isPresent());
            Optional<TaskRunDetail> last = latestRun(evaluation, taskId);
            last.map(TaskRunDetail::conversationId).ifPresent(publicId -> rememberConversation(evaluation, attempt, publicId));
            if (!done) {
                attempt.timedOut = true;
                if (attempt.conversationId != null) {
                    driver.stopConversation(evaluation, attempt.conversationId);
                }
            }
            ended = last;
        } finally {
            try {
                tasks.archive(evaluation, taskId);
            } catch (RuntimeException ex) {
                log.warn("배포 뒤 확인의 예약 작업을 보관 처리하지 못했다 taskId={}", taskId, ex);
            }
        }
        if (attempt.conversationId != null) {
            driver.settle(evaluation, attempt.conversationId);
        }
        String status = ended.map(run -> run.run().status().name()).orElse(null);
        Long executionId = ended.map(run -> run.run().executionId()).orElse(null);
        return new CheckObservation(List.of(), 0, false, status, executionId, attempt.timedOut);
    }


    private Optional<TaskRunDetail> latestRun(CurrentUser evaluation, UUID taskId) {
        return tasks.runs(evaluation, taskId, 1).stream().findFirst();
    }


    private void rememberConversation(CurrentUser evaluation, Attempt attempt, UUID publicId) {
        try {
            attempt.conversationId = chat.conversationOf(evaluation, publicId).id();
            attempt.conversationPublicId = publicId;
        } catch (ApiException ignored) {
            // 발화가 대화를 만들기 전에 끝났다. 지울 대화가 없다.
        }
    }


    private ChatAttachment uploadPhoto(CurrentUser evaluation, Long conversationId) {
        byte[] bytes;
        try (InputStream in = new ClassPathResource(DeployCheckItems.PHOTO_RESOURCE).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read the deploy check photo", ex);
        }
        return attachments.upload(
            evaluation, conversationId, "deploy-check-photo.png", "image/png", bytes.length, new ByteArrayResource(bytes));
    }
}
