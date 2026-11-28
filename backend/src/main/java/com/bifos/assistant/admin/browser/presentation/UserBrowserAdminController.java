package com.bifos.assistant.admin.browser.presentation;

import com.bifos.assistant.browser.application.BrowserAgentControl;
import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.admin.browser.presentation.UserBrowserAdminDtos.AdminAgentControlRequest;
import com.bifos.assistant.admin.browser.presentation.UserBrowserAdminDtos.AdminAgentControlView;
import com.bifos.assistant.admin.browser.presentation.UserBrowserAdminDtos.AdminBrowserView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자가 모든 브라우저의 상태를 보고 끄거나 지우는 경로다. 남의 화면은 열지 못한다.
 *
 * <p>{@code ADMIN} 만 부른다. 계약은 {@code docs/features/user-browser.md} 의 「API(사용자 브라우저)」 가 갖는다.
 */
@RestController
@RequestMapping("/api/v1/admin/browsers")
@RequiredArgsConstructor
public class UserBrowserAdminController {
    private final UserBrowserService browsers;
    private final UserDisplayNameService userNames;
    private final CurrentUserProvider currentUser;
    private final BrowserAgentControl agentControl;


    @GetMapping
    public List<AdminBrowserView> list() {
        currentUser.requireAdmin();
        return browsers.list().stream()
            .map(browser -> AdminBrowserView.of(browser, userNames.find(browser.userId())))
            .toList();
    }


    @PostMapping("/{id}/stop")
    public AdminBrowserView stop(@PathVariable Long id) {
        currentUser.requireAdmin();
        var browser = browsers.stopById(id);
        return AdminBrowserView.of(browser, userNames.find(browser.userId()));
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        currentUser.requireAdmin();
        browsers.deleteById(id);
        return ResponseEntity.noContent().build();
    }


    /** 그룹 전체의 에이전트 브라우저 조작 스위치다(docs/features/agent-browser/design.md 의 「PR1 스위치」). */
    @GetMapping("/agent-control")
    public AdminAgentControlView agentControl() {
        CurrentUser admin = currentUser.requireAdmin();
        return new AdminAgentControlView(agentControl.adminAllowed(admin.groupId()));
    }


    /** 끄면 켜 둔 사용자도 다음 호출과 기다리는 승인의 실행이 거절된다. 사용자 스위치 값은 지우지 않는다. */
    @PutMapping("/agent-control")
    public AdminAgentControlView setAgentControl(@RequestBody(required = false) AdminAgentControlRequest body) {
        CurrentUser admin = currentUser.requireAdmin();
        agentControl.setAdmin(admin.groupId(), AdminAgentControlRequest.required(body));
        return new AdminAgentControlView(agentControl.adminAllowed(admin.groupId()));
    }
}
