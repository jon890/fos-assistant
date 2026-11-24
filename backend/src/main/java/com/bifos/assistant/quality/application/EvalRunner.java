package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.TurnOutcome;
import com.bifos.assistant.quality.application.dto.EvalVerdict;
import com.bifos.assistant.quality.application.dto.EvalView;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.dto.ConnectorSummary;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.contentdraft.application.ContentDraftSaveApproval;
import com.bifos.assistant.mcp.application.McpConnectionLiveness;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.quality.application.dto.CheckObservation;
import com.bifos.assistant.quality.application.dto.EvalObservation;
import com.bifos.assistant.quality.application.dto.ToolObservation;
import com.bifos.assistant.quality.application.dto.TurnObservation;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.EvalBundle;
import com.bifos.assistant.quality.domain.EvalBundle.EvalQuestion;
import com.bifos.assistant.quality.domain.EvalBundle.EvalTurn;
import com.bifos.assistant.quality.domain.EvalResult.BundleResult;
import com.bifos.assistant.quality.domain.EvalResult.ItemResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.CardAction;
import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.task.application.TaskService;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 평가 한 번을 끝까지 돌린다(docs/features/agent-quality/design.md 의 「평가 한 번의 흐름」).
 *
 * <p>고른 묶음의 문항을 파일 순서대로 하나씩 평가 전용 사용자의 새 대화로 보낸다. 사람이 보낸 질문과 같은 {@link ChatService#send} 길이고, 승인 카드는
 * 화면 단추와 같은 승인 서비스로 누른다. 문항이 끝나면 판정하고 그 대화가 만든 것을 바로 비운다. 배포 뒤 확인과는 겹쳐도 된다(스펙 DECISION23).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EvalRunner {

    static final String RUN_FAILED = "RUN_FAILED";
    static final String BUSY = "BUSY";
    static final String TIMED_OUT = "TIMED_OUT";
    static final String CARD_FAILED = "CARD_FAILED";
    static final String RULES = "RULES";
    /** 평가가 도중에 멈춰 보내지 못한 문항이다. */
    static final String NOT_RUN = "NOT_RUN";
    /**
     * 평가 비서의 Control Plane MCP 연결이 끊긴 채 돌아 실패한 문항과, 그래서 보내지 않은 남은 문항이다. 품질 실패가 아니라 평가 환경의 문제다
     * (docs/features/agent-quality/design.md 의 「평가 환경 오류」).
     */
    static final String ENVIRONMENT = EvalView.ENVIRONMENT;

    /** 카드를 누른 뒤 에이전트가 다시 도는 turn 이 시작되기를 기다리는 상한이다. 결과만 대화에 들어가고 turn 이 없을 수도 있다. */
    static final Duration CARD_FOLLOW_UP_GRACE = Duration.ofSeconds(10);

    private final EvalRuns runs;
    private final QualityRunRepository rows;
    private final EvalCleaner cleaner;
    private final DeployCheckCleaner conversations;
    private final DeployCheckObserver observer;
    private final TurnDriver driver;
    private final EvaluationUserService evaluationUsers;
    private final AppUserRepository users;
    private final ChatService chat;
    private final AttachmentService attachments;
    private final TaskService tasks;
    private final ConnectorActionRepository connectorActions;
    private final ConnectorActionService actionService;
    private final ContentDraftSaveApproval contentDrafts;
    private final ConnectorConnectionService connections;
    private final McpConnectionLiveness liveness;
    private final BackgroundTasks background;
    private final LiveProperties<QualityProperties> properties;
    private final Clock clock;


    /** 평가 줄 하나를 요청 밖에서 돌린다. 줄은 {@link EvalRuns#open} 이 먼저 만든다. */
    void launch(Long runId, Long evaluationUserId, List<EvalBundle> bundles, ModelTier tier) {
        background.start("eval-run-" + runId, () -> run(runId, evaluationUserId, bundles, tier));
    }


    /**
     * 묶음을 차례로 돌리고 줄을 끝낸다. 도중의 예외는 남은 문항을 돌리지 않고 줄을 끝낸다. 한 문항이 환경 오류로 끝나면 남은 문항도 보내지 않고 환경 오류로
     * 적는다. 연결이 끊긴 채 보내면 모델 호출만 쓰고 같은 실패가 되풀이된다.
     */
    void run(Long runId, Long evaluationUserId, List<EvalBundle> bundles, ModelTier tier) {
        boolean cleanedAll = true;
        boolean stopped = false;
        String unrunReason = NOT_RUN;
        try {
            cleaner.recleanFinished();
            CurrentUser evaluation = EvaluationUserService.asCurrentUser(users.findById(evaluationUserId).orElseThrow());
            Agent primary = evaluationUsers.primaryAgent(evaluation)
                .orElseThrow(() -> new IllegalStateException("evaluation user has no primary agent"));
            Map<String, String> slots = slots(evaluation);
            for (EvalBundle bundle : bundles) {
                Duration timeout = Optional.ofNullable(properties.current().evalTimeoutOverride())
                    .orElse(Duration.ofMinutes(bundle.timeoutMinutes()));
                for (EvalQuestion question : bundle.questions()) {
                    if (ENVIRONMENT.equals(unrunReason)) {
                        break;
                    }
                    QuestionEnd end = runQuestion(runId, bundle, question, evaluation, primary, tier, slots, timeout);
                    cleanedAll &= end.cleaned();
                    if (end.environment()) {
                        log.warn("평가 비서의 Control Plane MCP 연결이 끊겨 남은 문항을 보내지 않는다 runId={} profile={}", runId,
                            primary.hermesProfile());
                        unrunReason = ENVIRONMENT;
                    }
                }
            }
        } catch (RuntimeException ex) {
            cleanedAll = false;
            stopped = true;
            log.error("평가가 도중에 멈췄다 runId={}", runId, ex);
        } finally {
            finish(runId, bundles, cleanedAll, stopped, unrunReason);
        }
    }


    /**
     * 묶음 결과로 줄을 끝낸다. 돌지 못한 문항은 실패(까닭 {@code unrunReason})로 적어 묶음 결과의 분모와 오류에 넣는다. 적지 않으면 멈춘 평가가 앞 문항만으로
     * 통과로 끝난다. 도중에 멈췄으면 묶음 결과와 상관없이 줄을 실패로 끝낸다.
     *
     * @param unrunReason 돌지 못한 문항의 까닭. 보통 {@code NOT_RUN} 이고, 환경 오류로 멈췄으면 {@code ENVIRONMENT} 다
     */
    private void finish(Long runId, List<EvalBundle> bundles, boolean cleanedAll, boolean stopped, String unrunReason) {
        try {
            QualityRun run = rows.findById(runId).orElseThrow();
            for (EvalBundle bundle : bundles) {
                for (EvalQuestion question : bundle.questions()) {
                    boolean recorded = run.evalResult().items().stream()
                        .anyMatch(item -> item.bundle().equals(bundle.id()) && item.question().equals(question.id()));
                    if (!recorded) {
                        runs.record(runId, new ItemResult(bundle.id(), question.id(), EvalItemStatus.FAILED, unrunReason, List.of(), 0, null, null, null,
                            List.of(), question.keepConversation()), 0);
                    }
                }
            }
            List<ItemResult> items = rows.findById(runId).orElseThrow().evalResult().items();
            List<BundleResult> results = bundles.stream()
                .map(bundle -> EvalJudge.bundleResult(bundle.id(), bundle.pass(),
                    items.stream().filter(item -> item.bundle().equals(bundle.id())).toList()))
                .toList();
            runs.finish(runId, results, stopped);
        } catch (RuntimeException ex) {
            log.error("평가 줄을 끝내지 못했다 runId={}", runId, ex);
        }
        if (cleanedAll) {
            try {
                runs.markCleaned(runId);
            } catch (RuntimeException ex) {
                log.warn("평가 줄에 비우기 끝남을 적지 못했다 runId={}", runId, ex);
            }
        }
    }


    /**
     * 문항 하나를 돌리고 판정하고 비운다. 통과하지 못했는데 평가 비서의 Control Plane MCP 연결이 끊겨 있으면 까닭을 환경 오류로 바꾼다.
     *
     * @return 만든 것을 모두 지웠는지와 환경 오류였는지
     */
    private QuestionEnd runQuestion(Long runId, EvalBundle bundle, EvalQuestion original, CurrentUser evaluation, Agent primary, ModelTier tier,
        Map<String, String> slots, Duration timeout) {
        String bundleId = bundle.id();
        Optional<EvalQuestion> filled = EvalBundles.fill(original, slots);
        if (filled.isEmpty()) {
            runs.record(runId, new ItemResult(bundleId, original.id(), EvalItemStatus.SKIPPED, null, List.of(), 0, null, null, null, List.of(),
                original.keepConversation()), 0);
            return new QuestionEnd(true, false);
        }
        EvalQuestion question = filled.get();
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        Instant deadline = startedAt.plus(timeout);
        Conversation conversation = chat.startEmpty(evaluation, primary.code());
        Long conversationId = conversation.id();
        runs.record(runId, new ItemResult(bundleId, question.id(), EvalItemStatus.RUNNING, null, List.of(), 0, startedAt, conversationId,
            conversation.publicId(), List.of(), question.keepConversation()), 0);
        EvalItemStatus status;
        String reason = null;
        List<String> failures = List.of();
        try {
            if (tier != null) {
                // 대화의 단계만 바꾼다. 사람 화면의 단계 고르기는 사용자 기본 단계까지 바꿔, 다음 평가와 배포 뒤 확인의 단계가 움직인다.
                chat.chooseTierForTask(conversationId, tier);
            }
            Attempt attempt = converse(evaluation, conversationId, question, deadline);
            driver.settle(evaluation, conversationId);
            if (attempt.outcome == TurnOutcome.TIMED_OUT) {
                status = EvalItemStatus.TIMED_OUT;
                reason = TIMED_OUT;
            } else if (attempt.outcome == TurnOutcome.BUSY) {
                status = EvalItemStatus.FAILED;
                reason = BUSY;
            } else if (attempt.outcome == TurnOutcome.REJECTED) {
                status = EvalItemStatus.FAILED;
                reason = RUN_FAILED;
            } else if (attempt.cardFailed) {
                status = EvalItemStatus.FAILED;
                reason = CARD_FAILED;
            } else {
                EvalObservation observation = observe(evaluation, conversationId, attempt.lastCard);
                if (bundle.pass().external()) {
                    // 판정은 밖의 채점기가 남긴 대화를 읽어 한다. 여기서는 답을 받았는지만 본다.
                    boolean answered = !observation.answer().isBlank();
                    status = answered ? EvalItemStatus.PASSED : EvalItemStatus.FAILED;
                    reason = answered ? null : EvalJudge.NO_ANSWER;
                } else {
                    EvalVerdict verdict = EvalJudge.judge(question.expect(), observation);
                    status = verdict.passed() ? EvalItemStatus.PASSED : EvalItemStatus.FAILED;
                    reason = verdict.passed() ? null : RULES;
                    failures = verdict.failures();
                }
            }
        } catch (RuntimeException ex) {
            log.warn("평가 문항이 예외로 끝났다 bundle={} question={}", bundleId, question.id(), ex);
            driver.stopConversation(evaluation, conversationId);
            status = EvalItemStatus.FAILED;
            reason = RUN_FAILED;
        }
        boolean environment = status != EvalItemStatus.PASSED && !liveness.connected(primary.hermesProfile());
        if (environment) {
            // 도구를 받지 못한 채 돈 실패는 에이전트의 회귀가 아니다. 규칙 미달 목록을 비워 판정까지 가지 못한 문항(묶음의 오류)으로 센다.
            status = EvalItemStatus.FAILED;
            reason = ENVIRONMENT;
            failures = List.of();
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        boolean cleaned = false;
        boolean dropped = false;
        List<Long> executionIds = List.of();
        int modelCalls = 0;
        try {
            executionIds = observer.roots(conversationId).stream().map(AgentExecution::id).toList();
            modelCalls = observer.modelCalls(conversationId);
            cleaned = settleAndClean(evaluation, conversationId, startedAt);
            dropped = status == EvalItemStatus.PASSED && !question.keepConversation() && conversations.dropConversation(evaluation, conversationId);
        } catch (RuntimeException ex) {
            // 결과를 적지 못하고 RUNNING 으로 남지 않게 실패로 적는다. 남은 것은 다음 시작과 하루 정리가 다시 비운다.
            log.warn("평가 문항을 마무리하지 못했다 bundle={} question={}", bundleId, question.id(), ex);
            status = EvalItemStatus.FAILED;
            reason = RUN_FAILED;
        }
        runs.record(runId, new ItemResult(bundleId, question.id(), status, reason, failures, elapsedMs, startedAt, conversationId,
            dropped ? null : conversation.publicId(), dropped ? List.of() : executionIds, question.keepConversation()), modelCalls);
        return new QuestionEnd(cleaned, environment);
    }


    /** 문항 하나가 끝난 모양이다. */
    private record QuestionEnd(boolean cleaned, boolean environment) {
    }


    /**
     * 남긴 카드를 먼저 거절로 닫고, 그 거절로 에이전트가 다시 도는 turn 과 위임 결과 전달이 끝나기를 기다린 뒤 문항 대화가 만든 것을 비운다. 기다리지 않으면
     * 그 뒤에 돈 turn 이 만든 예약 작업이나 기억이 「비우기 끝남」 뒤에 남는다.
     */
    private boolean settleAndClean(CurrentUser evaluation, Long conversationId, Instant startedAt) {
        int rootsBefore = observer.roots(conversationId).size();
        boolean pending = !connectorActions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(
            conversationId, evaluation.id(), ActionStatus.PENDING).isEmpty();
        boolean rejected = cleaner.rejectPending(evaluation, conversationId);
        if (pending) {
            // 거절한 줄이 있을 때만 다시 도는 turn 을 기다린다. 없으면 루트가 늘지 않아 기다림이 헛돈다.
            driver.waitUntil(clock.instant().plus(CARD_FOLLOW_UP_GRACE), () -> observer.roots(conversationId).size() > rootsBefore);
        }
        boolean settled = driver.waitUntil(clock.instant().plus(TurnDriver.STOP_GRACE),
            () -> !driver.running(conversationId) && observer.delegationSettled(conversationId));
        if (!settled) {
            driver.stopConversation(evaluation, conversationId);
        }
        boolean cleaned = cleaner.cleanQuestion(evaluation, conversationId, startedAt, null);
        return rejected & cleaned;
    }


    /** 문항의 차례를 보내고 차례마다 뜬 승인 카드를 처리한다. 시간 초과나 거절이면 남은 차례를 보내지 않는다. */
    private Attempt converse(CurrentUser evaluation, Long conversationId, EvalQuestion question, Instant deadline) {
        Attempt attempt = new Attempt();
        Set<UUID> seen = new HashSet<>();
        for (EvalTurn turn : question.turns()) {
            List<Long> attachmentIds = turn.attachment() == null ? List.of() : List.of(upload(evaluation, conversationId, turn.attachment()));
            attempt.outcome = driver.send(evaluation, conversationId, turn.text(), attachmentIds, deadline);
            if (attempt.outcome != TurnOutcome.DONE || !settle(evaluation, conversationId, deadline, attempt)) {
                return attempt;
            }
            CardAction action = turn.card() == null ? CardAction.LEAVE : turn.card();
            boolean pressed = false;
            for (ConnectorAction card : connectorActions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(
                conversationId, evaluation.id(), ActionStatus.PENDING)) {
                if (!seen.add(card.publicId())) {
                    continue;
                }
                if (action == CardAction.LEAVE) {
                    continue;
                }
                if (contentDrafts.select(evaluation, card.publicId()).isPresent()) {
                    // 원고 저장 승인은 화면이 다른 길로 승인한다. 평가가 누르지 않는다.
                    attempt.cardFailed = true;
                    return attempt;
                }
                try {
                    if (action == CardAction.APPROVE) {
                        actionService.approve(evaluation, card.publicId(), null, false);
                    } else {
                        actionService.reject(evaluation, card.publicId());
                    }
                    attempt.lastCard = card.publicId();
                    pressed = true;
                } catch (RuntimeException ex) {
                    log.warn("평가 문항의 승인 카드를 누르지 못했다 actionId={}", card.publicId(), ex);
                    attempt.cardFailed = true;
                    return attempt;
                }
            }
            if (pressed) {
                int rootsBefore = observer.roots(conversationId).size();
                Instant graceEnd = clock.instant().plus(CARD_FOLLOW_UP_GRACE);
                driver.waitUntil(graceEnd.isBefore(deadline) ? graceEnd : deadline, () -> observer.roots(conversationId).size() > rootsBefore);
                if (!settle(evaluation, conversationId, deadline, attempt)) {
                    return attempt;
                }
            }
        }
        return attempt;
    }


    /** 대화에 도는 turn 이 없고 위임 결과가 전해질 때까지 기다린다. 마감이 지나면 멈추고 시간 초과로 적는다. */
    private boolean settle(CurrentUser evaluation, Long conversationId, Instant deadline, Attempt attempt) {
        boolean settled = driver.waitUntil(deadline, () -> !driver.running(conversationId) && observer.delegationSettled(conversationId));
        if (!settled) {
            attempt.outcome = TurnOutcome.TIMED_OUT;
            driver.stopConversation(evaluation, conversationId);
        }
        return settled;
    }


    /** 판정에 쓸 값을 모은다. 답은 마지막으로 글이 있는 답이고, 도구는 문항의 모든 루트 트리에서 끝난 Control Plane 도구다. */
    private EvalObservation observe(CurrentUser evaluation, Long conversationId, UUID lastCard) {
        CheckObservation observation = observer.observe(evaluation, conversationId, false);
        String answer = "";
        List<String> toolNames = new ArrayList<>();
        for (TurnObservation turn : observation.turns()) {
            if (turn.answer() != null && !turn.answer().isBlank()) {
                answer = turn.answer();
            }
            turn.tools().stream().map(ToolObservation::toolName).forEach(toolNames::add);
        }
        List<String> instructions = new ArrayList<>();
        for (String taskId : EvalJudge.linkedTaskIds(answer)) {
            try {
                instructions.add(tasks.get(evaluation, UUID.fromString(taskId)).task().instruction());
            } catch (RuntimeException ex) {
                // 링크가 가리키는 작업이 없거나 남의 것이다. 지시문 없이 판정한다.
            }
        }
        String cardStatus = lastCard == null ? null
            : connectorActions.findByPublicId(lastCard).map(card -> card.status() == null ? null : card.status().name()).orElse(null);
        return new EvalObservation(answer, EvalJudge.controlPlaneTools(toolNames), instructions, cardStatus);
    }


    /**
     * 문항 자리의 값이다. 다른 에이전트는 평가 사용자의 위임 대상 에이전트, 플러그인은 그룹 카탈로그에서 평가 사용자가 연결하지 않은 첫 플러그인이다(스펙
     * DECISION35). 값이 없으면 그 자리를 쓰는 문항은 건너뛴다.
     */
    private Map<String, String> slots(CurrentUser evaluation) {
        Map<String, String> values = new HashMap<>();
        evaluationUsers.delegateAgent(evaluation).ifPresent(agent -> {
            values.put("otherAgentCode", agent.code());
            values.put("otherAgentName", agent.name());
        });
        try {
            connections.catalog(evaluation).stream()
                .filter(ConnectorSummary::available)
                .filter(summary -> summary.myStatus() == null || summary.myStatus() == ConnectionStatus.DISCONNECTED)
                .findFirst()
                .ifPresent(summary -> {
                    values.put("connectorId", summary.id());
                    values.put("connectorTitle", summary.title());
                });
        } catch (RuntimeException ex) {
            log.warn("평가 문항의 플러그인 자리 값을 읽지 못했다", ex);
        }
        return values;
    }


    private Long upload(CurrentUser evaluation, Long conversationId, String name) {
        byte[] bytes;
        try (InputStream in = new ClassPathResource("quality/" + name).getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read the eval attachment " + name, ex);
        }
        String type = name.endsWith(".png") ? "image/png" : "image/jpeg";
        return attachments.upload(evaluation, conversationId, name, type, bytes.length, new ByteArrayResource(bytes)).id();
    }


    /** 문항 하나가 도는 동안 모으는 값이다. */
    private static final class Attempt {
        private TurnOutcome outcome = TurnOutcome.DONE;
        private boolean cardFailed;
        private UUID lastCard;
    }
}
