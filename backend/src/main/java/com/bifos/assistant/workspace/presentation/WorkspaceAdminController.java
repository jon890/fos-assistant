package com.bifos.assistant.workspace.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.workspace.application.WorkspaceUsageService;
import com.bifos.assistant.workspace.presentation.WorkspaceDtos.AdminUsageView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자가 실행 공간마다 용량을 보는 경로다. 파일 이름과 경로는 주지 않는다.
 *
 * <p>{@code ADMIN} 만 부른다. 계약은 {@code backend/docs/code-architecture.md} 의 「관리자 용량」 이 갖는다.
 */
@RestController
@RequestMapping("/api/v1/admin/workspaces")
@RequiredArgsConstructor
public class WorkspaceAdminController {
    private final WorkspaceUsageService usage;
    private final CurrentUserProvider currentUser;

    /** 요청할 때 센다. 루트가 설정되지 않아도 200 이고 {@code available} 이 거짓이다. */
    @GetMapping
    public AdminUsageView report() {
        currentUser.requireAdmin();
        return AdminUsageView.of(usage.report());
    }
}
