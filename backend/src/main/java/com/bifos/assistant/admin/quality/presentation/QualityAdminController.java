package com.bifos.assistant.admin.quality.presentation;

import com.bifos.assistant.admin.quality.presentation.QualityAdminDtos.EvaluationUserView;
import com.bifos.assistant.admin.quality.presentation.QualityAdminDtos.PrepareEvaluationUserRequest;
import com.bifos.assistant.quality.application.EvaluationUserService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포 뒤 확인이 쓰는 평가 전용 사용자를 준비하고 상태를 읽는 자리다(docs/features/agent-quality/design.md 의 「관리자 API」).
 *
 * <p>{@code ADMIN} 만 부른다. 준비는 다시 불러도 빠진 것만 채운다.
 */
@RestController
@RequestMapping("/api/v1/admin/quality")
@RequiredArgsConstructor
public class QualityAdminController {

    private final EvaluationUserService evaluationUsers;
    private final CurrentUserProvider currentUser;


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
}
