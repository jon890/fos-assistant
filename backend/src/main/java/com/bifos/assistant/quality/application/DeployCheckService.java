package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.DeployCheckPage;
import com.bifos.assistant.quality.application.dto.DeployCheckView;
import com.bifos.assistant.quality.application.dto.OpenedDeployCheck;
import com.bifos.assistant.quality.application.dto.StartedDeployCheck;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.tree.application.ExecutionTreeService;
import com.bifos.assistant.usage.tree.application.dto.ExecutionTree;
import com.bifos.assistant.user.domain.AppUser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 배포 뒤 확인을 시작하고 읽는다(docs/features/agent-quality/design.md 의 「관리자 API」). 관리자인지는 부르는 쪽이 먼저 확인한다.
 *
 * <p>그룹에 도는 확인은 하나다. 도는 확인이 있으면 새로 만들지 않고 그 줄을 돌려준다. 같은 배포에서 두 번 불러도 하나다.
 */
@Service
@RequiredArgsConstructor
public class DeployCheckService {

    /** 목록 한 쪽의 크기다. */
    static final int PAGE_SIZE = 20;

    private final EvaluationUserService evaluationUsers;
    private final DeployCheckRuns runs;
    private final DeployCheckRunner runner;
    private final QualityRunRepository rows;
    private final ExecutionTreeService trees;


    /**
     * 배포 파이프라인이 확인을 시작한다. 파이프라인은 관리자 토큰으로 부른다. 새로 만든 줄이면 요청 밖에서 기능을 차례로 돌린다.
     *
     * @throws ApiException {@code EVALUATION_USER_MISSING}. 그룹에 평가 전용 사용자가 없다
     */
    public StartedDeployCheck start(CurrentUser admin) {
        return start(admin, QualityRunTrigger.PIPELINE);
    }


    private StartedDeployCheck start(CurrentUser admin, QualityRunTrigger trigger) {
        AppUser evaluation = evaluationUsers.find(admin.groupId())
            .orElseThrow(() -> new ApiException(ErrorCode.EVALUATION_USER_MISSING, "prepare the evaluation user first"));
        OpenedDeployCheck opened = runs.open(evaluation.id(), trigger, admin.id());
        if (opened.started().created()) {
            runner.launch(opened.runId(), evaluation.id());
        }
        return opened.started();
    }


    /** 같은 기능 묶음으로 새 확인을 시작한다. 고른 확인이 그룹의 것이어야 한다. */
    public StartedDeployCheck retry(CurrentUser admin, UUID publicId) {
        require(admin, publicId);
        return start(admin, QualityRunTrigger.ADMIN);
    }


    public DeployCheckView get(CurrentUser admin, UUID publicId) {
        return DeployCheckView.of(require(admin, publicId));
    }


    /**
     * 최근 순으로 한 쪽 읽는다.
     *
     * @param cursor 앞 쪽의 {@code nextCursor}. 처음이면 null
     */
    public DeployCheckPage list(CurrentUser admin, String cursor) {
        PageRequest page = PageRequest.of(0, PAGE_SIZE + 1);
        List<QualityRun> found = cursor == null || cursor.isBlank()
            ? rows.findByGroupIdAndKindOrderByIdDesc(admin.groupId(), QualityRunKind.DEPLOY_CHECK, page)
            : rows.findByGroupIdAndKindAndIdLessThanOrderByIdDesc(
                admin.groupId(), QualityRunKind.DEPLOY_CHECK, require(admin, parse(cursor)).id(), page);
        boolean more = found.size() > PAGE_SIZE;
        List<QualityRun> shown = more ? found.subList(0, PAGE_SIZE) : found;
        String next = more ? shown.getLast().publicId().toString() : null;
        return new DeployCheckPage(shown.stream().map(DeployCheckView::of).toList(), next);
    }


    /**
     * 평가 전용 사용자의 실행 트리를 연다. 다른 사용자의 실행은 없는 것과 같다(이 기능 설계 DECISION8).
     *
     * @throws ApiException {@code EXECUTION_NOT_FOUND}
     */
    public ExecutionTree tree(CurrentUser admin, Long executionId) {
        AppUser evaluation = evaluationUsers.find(admin.groupId())
            .orElseThrow(() -> new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "no such execution"));
        return trees.ofOwner(evaluation.id(), admin, executionId);
    }


    private QualityRun require(CurrentUser admin, UUID publicId) {
        return rows.findByPublicId(publicId)
            .filter(run -> run.groupId().equals(admin.groupId()) && run.kind() == QualityRunKind.DEPLOY_CHECK)
            .orElseThrow(() -> new ApiException(ErrorCode.QUALITY_CHECK_NOT_FOUND, "no such quality check"));
    }


    private static UUID parse(String cursor) {
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid cursor");
        }
    }
}
