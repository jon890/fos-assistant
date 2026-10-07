package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.AdminBrowserView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.application.UserDisplayNameService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자가 모든 브라우저의 상태를 보고 끄거나 지우는 경로다. 남의 화면은 열지 못한다.
 *
 * <p>{@code ADMIN} 만 부른다. 계약은 {@code docs/backend/user-browser.md} 의 「API」 가 갖는다.
 */
@RestController
@RequestMapping("/api/v1/admin/browsers")
@RequiredArgsConstructor
public class UserBrowserAdminController {
    private final UserBrowserService browsers;
    private final UserDisplayNameService userNames;
    private final CurrentUserProvider currentUser;

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
}
