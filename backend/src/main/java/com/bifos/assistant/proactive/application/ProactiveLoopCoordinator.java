package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.domain.AutonomyDecision;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.ProactiveLoopSetting;
import com.bifos.assistant.proactive.domain.ValueEvaluation;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.domain.type.LoopSkippedReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매일 깨우기 살펴보기가 끝나면 동의한 사용자에게만 가치 평가와 행동 정책을 한 번 잇는다(ADR-20261008 / daily-loop).
 *
 * <p>언제 부르는지와 시도 줄만 갖는다. 평가와 판정의 검사, 저장, 실패 처리는 {@link ValueEvaluationService} 와
 * {@link AutonomyPolicyService} 가 갖는다. 순서와 조건은 {@code docs/backend/proactive-loop.md} 가 갖는다.
 *
 * <p>사건은 살펴보기 turn 을 돌린 백그라운드 스레드에서 동기로 받는다. 시도는 다시 부르지 않는다. 실패한 시도는 {@code FAILED} 로 끝이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveLoopCoordinator {

    /**
     * 하루 상한을 세는 창이다. 시도 줄의 시각은 turn 이 끝난 뒤라 날마다 다르다. 24시간이면 어제보다 일찍 끝난 오늘 깨우기가 어제 줄에 걸린다.
     */
    static final Duration DAILY_WINDOW = Duration.ofHours(20);

    static final String INTERNAL_ERROR = ErrorCode.INTERNAL_ERROR.name();

    private final ProactiveCheckRepository checks;
    private final ProactiveCheckProblemRepository problems;
    private final ProactiveLoopSettingRepository settings;
    private final ProactiveLoopRunRepository runs;
    private final ValueEvaluationService evaluations;
    private final AutonomyPolicyService autonomy;
    private final SurfacedProblems surfacedProblems;
    private final LiveProperties<ProactiveLoopProperties> properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @EventListener
    public void settled(ProactiveCheckSettled event) {
        ProactiveCheck check = checks.findById(event.checkId()).orElse(null);
        if (check == null || check.trigger() != CheckTrigger.SCHEDULED || check.status() != CheckStatus.SUCCEEDED) {
            return;
        }
        ProactiveLoopProperties loop = properties.current();
        if (!loop.enabled() || !consented(check)) {
            return;
        }
        ProactiveLoopRun run;
        try {
            run = transactions.execute(status -> begin(check, loop.maxRunsPerDay()));
        } catch (DataIntegrityViolationException ex) {
            skipOnViolation(check.id(), ex);
            return;
        }
        if (run == null || run.status() != LoopRunStatus.RUNNING) {
            return;
        }
        evaluateAndDecide(event.user(), check.id(), run.id(), loop.provider());
    }

    /**
     * 시도 줄을 저장하지 못했다. 그 원천의 줄이 있으면 원천 유일 제약이고 이미 다른 처리가 이 살펴보기를 맡았다. 없으면 점검 대화나 살펴보기가
     * 지워진 것처럼 원천 중복이 아닌 저장 실패다. 어느 쪽이든 다시 부르지 않는다.
     */
    private void skipOnViolation(Long checkId, DataIntegrityViolationException ex) {
        boolean taken;
        try {
            taken = runs.findBySourceCheckId(checkId).isPresent();
        } catch (RuntimeException readFailure) {
            taken = false;
        }
        if (taken) {
            log.info("이미 이어진 살펴보기라 매일 루프를 건너뛴다 checkId={}", checkId);
        } else {
            log.warn(
                    "매일 루프 시도 줄을 저장하지 못했다 checkId={} error={}",
                    checkId,
                    ex.getClass().getSimpleName());
        }
    }

    /** 잠그기 전 읽기다. 동의하지 않은 사용자의 살펴보기마다 잠금을 잡지 않게 먼저 거른다. */
    private boolean consented(ProactiveCheck check) {
        return settings.findByUserIdAndAgentId(check.userId(), check.agentId())
                .map(ProactiveLoopSetting::enabled)
                .orElse(false);
    }

    /**
     * 그 사용자의 설정 줄을 모두 쓰기 잠금으로 잡고 시도 줄을 저장한다. 같은 사용자의 두 깨우기가 하루 상한을 함께 넘지 않게 같은 트랜잭션에서 센다.
     *
     * @return 저장한 시도 줄. 잠근 뒤 설정이 꺼졌거나 지워졌으면 null
     */
    private ProactiveLoopRun begin(ProactiveCheck check, int maxRunsPerDay) {
        Long userId = check.userId();
        ProactiveLoopSetting setting = settings.findByUserIdOrderByIdAsc(userId).stream()
                .filter(each -> each.agentId().equals(check.agentId()))
                .findFirst()
                .orElse(null);
        if (setting == null || !setting.enabled()) {
            return null;
        }
        Instant now = clock.instant();
        ProactiveLoopRun row;
        if (setting.snoozedAt(now)) {
            row = ProactiveLoopRun.skipped(userId, check.id(), LoopSkippedReason.SNOOZED, now);
        } else if (problems.findByCheckIdAndStatusOrderByIdAsc(check.id(), ProblemStatus.ACCEPTED)
                .isEmpty()) {
            row = ProactiveLoopRun.skipped(userId, check.id(), LoopSkippedReason.NO_CANDIDATE, now);
        } else if (runs.countByUserIdAndStatusNotAndCreatedAtAfter(
                        userId, LoopRunStatus.SKIPPED, now.minus(DAILY_WINDOW))
                >= maxRunsPerDay) {
            row = ProactiveLoopRun.skipped(userId, check.id(), LoopSkippedReason.DAILY_LIMIT, now);
        } else {
            row = ProactiveLoopRun.running(userId, check.id(), now);
        }
        return runs.saveAndFlush(row);
    }

    /**
     * 트랜잭션 밖에서 평가와 판정을 부른다. 모델을 기다리는 동안 잠금을 쥐지 않는다. 시도를 {@code DECIDED} 로 적은 뒤에 보일 판정의
     * {@code SURFACED} 를 남기고, 그 실패는 시도의 결과를 바꾸지 않는다.
     */
    private void evaluateAndDecide(CurrentUser user, Long checkId, Long runId, String provider) {
        Long evaluationId = null;
        List<AutonomyDecision> decided;
        try {
            ValueEvaluation evaluation = evaluations.evaluate(user, checkId, provider);
            evaluationId = evaluation.id();
            decided = autonomy.decide(user, evaluationId);
            Long decidedEvaluation = evaluationId;
            finish(runId, row -> row.decided(decidedEvaluation, clock.instant()));
        } catch (ApiException ex) {
            String code = ex.code().name();
            Long evaluated = evaluationId;
            log.warn("매일 루프의 평가나 판정이 거절됐다 checkId={} runId={} code={}", checkId, runId, code);
            finish(runId, row -> row.failed(code, evaluated, clock.instant()));
            return;
        } catch (RuntimeException ex) {
            Long evaluated = evaluationId;
            log.warn(
                    "매일 루프의 평가나 판정이 실패했다 checkId={} runId={} error={}",
                    checkId,
                    runId,
                    ex.getClass().getSimpleName());
            finish(runId, row -> row.failed(INTERNAL_ERROR, evaluated, clock.instant()));
            return;
        }
        for (AutonomyDecision decision : decided) {
            try {
                surfacedProblems.surfaced(decision);
            } catch (RuntimeException ex) {
                log.warn(
                        "보인 판정의 판단 피드백을 남기지 못했다 decisionId={} error={}",
                        decision.id(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    /** 시도 줄을 새 트랜잭션에서 다시 읽어 결과를 적는다. 적지 못해도 예외를 올리지 않는다. 남은 {@code RUNNING} 은 기동 복구가 닫는다. */
    private void finish(Long runId, Consumer<ProactiveLoopRun> change) {
        try {
            transactions.executeWithoutResult(status -> runs.findById(runId).ifPresent(change));
        } catch (RuntimeException ex) {
            log.warn(
                    "매일 루프 시도의 결과를 적지 못했다 runId={} error={}",
                    runId,
                    ex.getClass().getSimpleName());
        }
    }
}
