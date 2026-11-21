package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.OpenedEval;
import com.bifos.assistant.quality.domain.EvalResult;
import com.bifos.assistant.quality.domain.EvalResult.BundleResult;
import com.bifos.assistant.quality.domain.EvalResult.ItemResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 한 줄({@code kind} {@code EVAL})의 쓰기를 맡는다. 쓰기마다 짧은 트랜잭션 하나다.
 *
 * <p>실행기는 Hermes 를 기다리는 동안 트랜잭션을 쥐지 않는다. 문항을 시작할 때 대화 번호를 먼저 적고 끝날 때 결과를 적어, 도중에 서버가 다시 떠도
 * 비울 대상이 남는다(docs/features/agent-quality/design.md 의 「비우기」).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EvalRuns {

    static final String INTERRUPTED = "INTERRUPTED";

    private final QualityRunRepository runs;
    private final AppUserRepository users;
    private final Clock clock;


    /**
     * 도는 평가가 없으면 새 줄을 만든다. 평가 사용자 행을 잠근 뒤 찾으므로 두 요청이 겹쳐도 하나만 만든다. 도는 배포 뒤 확인은 막지 않는다(스펙 DECISION23).
     *
     * @throws ApiException {@code EVALUATION_USER_MISSING}. 평가 사용자 행이 없다
     */
    @Transactional
    public OpenedEval open(Long evaluationUserId, List<String> bundleIds, String tier, int itemCount, Long requestedByUserId) {
        AppUser evaluation = users.findByIdForUpdate(evaluationUserId)
            .orElseThrow(() -> new ApiException(ErrorCode.EVALUATION_USER_MISSING, "the evaluation user is missing"));
        return runs.findFirstByGroupIdAndKindAndStatusOrderByIdDesc(evaluation.groupId(), QualityRunKind.EVAL, QualityRunStatus.RUNNING)
            .map(running -> new OpenedEval(running, false))
            .orElseGet(() -> new OpenedEval(runs.save(QualityRun.startEval(evaluation.groupId(), QualityRunTrigger.ADMIN, requestedByUserId,
                itemCount, EvalResult.started(tier, bundleIds), clock.instant())), true));
    }


    /** 문항 결과를 적는다. 같은 문항의 앞 줄(시작 때 적은 줄)을 바꾼다. */
    @Transactional
    public void record(Long runId, ItemResult item, int modelCalls) {
        QualityRun run = runs.findById(runId).orElseThrow();
        run.recordEval(run.evalResult().withItem(item), modelCalls);
    }


    /** 묶음 결과를 적고 줄을 끝낸다. 고른 묶음이 모두 통과해야 {@code PASSED} 다. */
    @Transactional
    public QualityRun finish(Long runId, List<BundleResult> bundles) {
        QualityRun run = runs.findById(runId).orElseThrow();
        if (!run.running()) {
            return run;
        }
        run.recordEval(run.evalResult().withBundles(bundles), 0);
        boolean passed = !bundles.isEmpty() && bundles.stream().allMatch(bundle -> "PASSED".equals(bundle.status()));
        run.finish(passed ? QualityRunStatus.PASSED : QualityRunStatus.FAILED, clock.instant());
        return run;
    }


    /** 모든 문항이 만든 것을 지웠다고 적는다. 끝난 줄에도 적는다. */
    @Transactional
    public void markCleaned(Long runId) {
        runs.findById(runId).ifPresent(run -> run.replaceEval(run.evalResult().markCleaned()));
    }


    /** 보관 기간이 지나 지운 대화의 링크를 비운다. 지우지 못한 대화의 링크는 남긴다. */
    @Transactional
    public void dropLinks(Long runId, Set<UUID> removed) {
        runs.findById(runId).ifPresent(run -> run.replaceEval(run.evalResult().withItems(item -> item.conversationPublicId() != null && removed.contains(item.conversationPublicId()) ? item.withoutLinks() : item)));
    }


    /** 비우기가 끝나지 않은 끝난 평가 줄이다. 다음 시작, 재기동, 하루 정리가 다시 비운다. */
    @Transactional(readOnly = true)
    public List<Long> uncleaned() {
        return runs.findByKindAndStatusNot(QualityRunKind.EVAL, QualityRunStatus.RUNNING).stream()
            .filter(run -> !run.evalResult().cleaned())
            .map(QualityRun::id)
            .toList();
    }


    /**
     * 서버가 다시 떠 끊긴 평가를 끝낸다. 끝내지 못한 문항은 실패, 까닭 {@code INTERRUPTED} 로 적고 다시 돌리지 않는다(스펙 DECISION37). 알림은 보내지
     * 않는다. 남은 것은 {@link EvalCleaner} 가 이어 비운다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void interruptRunning() {
        for (QualityRun run : runs.findByKindAndStatus(QualityRunKind.EVAL, QualityRunStatus.RUNNING)) {
            EvalResult result = run.evalResult().withItems(item -> item.status() == EvalItemStatus.RUNNING
                ? new ItemResult(item.bundle(), item.question(), EvalItemStatus.FAILED, INTERRUPTED, List.of(), item.elapsedMs(),
                    item.startedAt(), item.conversationId(), item.conversationPublicId(), item.executionIds(), item.keep())
                : item);
            run.recordEval(result, 0);
            run.finish(QualityRunStatus.FAILED, clock.instant());
            log.warn("서버가 다시 떠 끊긴 평가를 실패로 끝냈다 runId={}", run.id());
        }
    }
}
