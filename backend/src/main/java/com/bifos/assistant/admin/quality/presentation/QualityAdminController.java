package com.bifos.assistant.admin.quality.presentation;

import com.bifos.assistant.admin.quality.presentation.QualityAdminDtos.EvaluationUserView;
import com.bifos.assistant.admin.quality.presentation.QualityAdminDtos.PrepareEvaluationUserRequest;
import com.bifos.assistant.quality.application.DeployCheckService;
import com.bifos.assistant.quality.application.EvaluationUserService;
import com.bifos.assistant.quality.application.dto.DeployCheckPage;
import com.bifos.assistant.quality.application.dto.DeployCheckView;
import com.bifos.assistant.quality.application.dto.StartedDeployCheck;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.usage.tree.application.dto.ExecutionTree;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포 뒤 확인을 시작하고 읽는 자리와, 확인이 쓰는 평가 전용 사용자를 준비하는 자리다(docs/features/agent-quality/design.md 의
 * 「관리자 API」).
 *
 * <p>{@code ADMIN} 만 부른다. 배포 파이프라인도 관리자 토큰으로 부른다. 준비는 다시 불러도 빠진 것만 채운다.
 */
@RestController
@RequestMapping("/api/v1/admin/quality")
@RequiredArgsConstructor
public class QualityAdminController {

    private final EvaluationUserService evaluationUsers;
    private final DeployCheckService checks;
    private final CurrentUserProvider currentUser;


    /** 확인을 시작한다. 새로 시작하면 202, 이미 도는 확인을 돌려주면 200 이다. */
    @PostMapping("/checks")
    public ResponseEntity<DeployCheckView> startCheck() {
        CurrentUser admin = currentUser.requireAdmin();
        return started(checks.start(admin));
    }


    @GetMapping("/checks")
    public DeployCheckPage checks(@RequestParam(required = false) String cursor) {
        CurrentUser admin = currentUser.requireAdmin();
        return checks.list(admin, cursor);
    }


    @GetMapping("/checks/{publicId}")
    public DeployCheckView check(@PathVariable UUID publicId) {
        CurrentUser admin = currentUser.requireAdmin();
        return checks.get(admin, publicId);
    }


    /** 품질 화면의 「다시 확인」 이다. 도는 확인이 있으면 그 줄을 200 으로 돌려준다. */
    @PostMapping("/checks/{publicId}/retry")
    public ResponseEntity<DeployCheckView> retryCheck(@PathVariable UUID publicId) {
        CurrentUser admin = currentUser.requireAdmin();
        return started(checks.retry(admin, publicId));
    }


    /** 평가 전용 사용자의 실행 트리만 연다. 다른 사용자의 실행은 404 다. */
    @GetMapping("/executions/{executionId}/tree")
    public ExecutionTree executionTree(@PathVariable Long executionId) {
        CurrentUser admin = currentUser.requireAdmin();
        return checks.tree(admin, executionId);
    }


    @GetMapping("/evaluation-user")
    public EvaluationUserView evaluationUser() {
        CurrentUser admin = currentUser.requireAdmin();
        return EvaluationUserView.of(evaluationUsers.status(admin));
    }


    @PostMapping("/evaluation-user")
    public EvaluationUserView prepareEvaluationUser(@Valid @RequestBody PrepareEvaluationUserRequest request) {
        CurrentUser admin = currentUser.requireAdmin();
        return EvaluationUserView.of(evaluationUsers.prepare(admin, request.hermesProfile()));
    }


    private static ResponseEntity<DeployCheckView> started(StartedDeployCheck started) {
        return ResponseEntity.status(started.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(started.check());
    }
}
