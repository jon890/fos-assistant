package com.bifos.assistant.quality.application;

import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
import com.bifos.assistant.people.application.SignInPolicy;
import com.bifos.assistant.quality.application.dto.DeployCheckView;
import com.bifos.assistant.quality.application.dto.OpenedDeployCheck;
import com.bifos.assistant.quality.application.dto.StartedDeployCheck;
import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.CheckReason;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배포 뒤 확인 한 줄({@code quality_run})의 쓰기를 맡는다. 쓰기마다 짧은 트랜잭션 하나다.
 *
 * <p>실행기는 Hermes 를 기다리는 동안 트랜잭션을 쥐지 않는다. 기능이 끝날 때마다 {@link #record} 로 결과를 더해, 도중에 서버가 다시
 * 떠도 실패한 기능의 대화 번호가 남는다(docs/features/agent-quality/design.md 의 「실행기 구현 메모」).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployCheckRuns {

    static final String FAILED_TITLE = "배포 뒤 확인이 실패했어요";

    private final QualityRunRepository runs;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final SignInPolicy signInPolicy;
    private final Clock clock;


    /**
     * 도는 확인이 없으면 새 줄을 만든다. 평가 사용자 행을 잠근 뒤 찾으므로 두 요청이 겹쳐도 하나만 만든다.
     *
     * @param evaluationUserId 평가 전용 사용자. 그룹마다 하나라 시작 잠금으로 쓴다
     * @return 만든 줄이나 이미 도는 줄과, 만들었는지
     * @throws ApiException {@code EVALUATION_USER_MISSING}. 평가 사용자 행이 없다
     */
    @Transactional
    public OpenedDeployCheck open(Long evaluationUserId, QualityRunTrigger trigger, Long requestedByUserId) {
        AppUser evaluation = users.findByIdForUpdate(evaluationUserId)
            .orElseThrow(() -> new ApiException(ErrorCode.EVALUATION_USER_MISSING, "the evaluation user is missing"));
        return runs.findFirstByGroupIdAndKindAndStatusOrderByIdDesc(
            evaluation.groupId(), QualityRunKind.DEPLOY_CHECK, QualityRunStatus.RUNNING)
            .map(running -> opened(running, false))
            .orElseGet(() -> opened(runs.save(QualityRun.start(
                evaluation.groupId(), QualityRunKind.DEPLOY_CHECK, trigger, requestedByUserId, clock.instant())), true));
    }


    private static OpenedDeployCheck opened(QualityRun run, boolean created) {
        return new OpenedDeployCheck(run.id(), new StartedDeployCheck(DeployCheckView.of(run), created));
    }


    /** 기능 하나의 결과를 더한다. */
    @Transactional
    public void record(Long runId, FeatureResult result, int modelCalls) {
        runs.findById(runId).orElseThrow().record(result, modelCalls);
    }


    /** 보관 기간이 지나 대화를 지운 기능의 대화·실행 링크를 비운다. 지우지 못한 대화의 링크는 남긴다. */
    @Transactional
    public void dropLinks(Long runId, Set<UUID> removed) {
        runs.findById(runId).ifPresent(run -> run.dropLinks(removed));
    }


    /**
     * 확인을 끝낸다. 기능 하나라도 통과하지 못했으면 {@code FAILED} 이고, 같은 트랜잭션에서 그룹의 관리자마다 알림을 남긴다.
     *
     * <p>다음 확인이 통과해도 앞 실패 알림을 읽음으로 바꾸지 않는다. 실패 기록은 사람이 보고 지운다.
     */
    @Transactional
    public QualityRun finish(Long runId) {
        QualityRun run = runs.findById(runId).orElseThrow();
        if (!run.running()) {
            return run;
        }
        List<FeatureResult> failed = run.results().stream()
            .filter(result -> result.status() != FeatureStatus.PASSED)
            .toList();
        boolean passed = failed.isEmpty() && !run.results().isEmpty();
        run.finish(passed ? QualityRunStatus.PASSED : QualityRunStatus.FAILED, clock.instant());
        if (!passed) {
            notifyAdmins(run, failed);
        }
        return run;
    }


    /**
     * 서버가 다시 떠 끊긴 확인을 끝낸다. 끝내지 못한 기능은 {@code INTERRUPTED} 로 적고 다시 돌리지 않는다(이 기능 설계 DECISION7).
     *
     * <p>배포 자체가 재기동이라 다시 돌리면 같은 배포의 확인이 겹친다. 파이프라인은 실패로 보고 사람이 「다시 확인」 한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void interruptRunning() {
        for (QualityRun run : runs.findByStatus(QualityRunStatus.RUNNING)) {
            Set<QualityFeature> done = run.results().stream().map(FeatureResult::feature).collect(Collectors.toSet());
            EnumSet<QualityFeature> left = EnumSet.allOf(QualityFeature.class);
            left.removeAll(done);
            for (QualityFeature feature : left) {
                run.record(new FeatureResult(feature, FeatureStatus.FAILED, CheckReason.INTERRUPTED.name(), 0, null, List.of()), 0);
            }
            run.finish(QualityRunStatus.FAILED, clock.instant());
            notifyAdmins(run, run.results().stream().filter(result -> result.status() != FeatureStatus.PASSED).toList());
            log.warn("서버가 다시 떠 끊긴 배포 뒤 확인을 실패로 끝냈다 runId={}", run.id());
        }
    }


    /** 본문은 실패한 기능 이름만 담는다. 모델이 쓴 글과 대화 내용은 넣지 않는다. */
    private void notifyAdmins(QualityRun run, List<FeatureResult> failed) {
        String body = failed.isEmpty()
            ? "확인한 기능이 없어요."
            : failed.stream().map(result -> featureLabel(result.feature())).collect(Collectors.joining(", ")) + " 확인이 통과하지 못했어요.";
        NotificationTarget target = new NotificationTarget(NotificationTargetType.ADMIN_QUALITY, null);
        for (AppUser admin : users.findByGroupIdAndRole(run.groupId(), UserRole.ADMIN)) {
            if (signInPolicy.revoked(admin.email())) {
                continue;
            }
            notifications.notify(admin.id(), NotificationKind.QUALITY_CHECK_FAILED, FAILED_TITLE, body, target, run.publicId());
        }
    }


    /** 알림 본문에 쓰는 기능 이름이다. 화면의 이름과 같다. */
    static String featureLabel(QualityFeature feature) {
        return switch (feature) {
            case SEARCH -> "검색과 출처";
            case DELEGATION -> "위임";
            case FILE_PUT -> "내 파일 넣기";
            case IMAGE -> "그림 만들기";
            case MEMORY_READ -> "기억 읽기";
            case TASK_FIRE -> "예약 작업 발화";
            case PHOTO_INSPECT -> "원본 사진 조회";
        };
    }
}
