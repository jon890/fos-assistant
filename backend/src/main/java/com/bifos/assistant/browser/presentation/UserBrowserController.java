package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.presentation.UserBrowserDtos.BrowserView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사람이 웹에서 쓰는 내 브라우저 경로다. 계약은 {@code docs/backend/user-browser.md} 의 「API」 가 갖는다.
 *
 * <p>주인은 웹 토큰의 사용자다. 요청 본문으로 사용자나 브라우저를 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/browser")
@RequiredArgsConstructor
public class UserBrowserController {
    private final UserBrowserService browsers;
    private final CurrentUserProvider currentUser;

    /** 기능이 꺼져 있어도 200 이다. 화면이 「준비 중」 을 그릴 수 있게 한다. */
    @GetMapping
    public BrowserView get() {
        Long userId = currentUser.require().id();
        if (!browsers.enabled()) {
            return BrowserView.disabled();
        }
        return browsers.get(userId)
                .map(browser -> BrowserView.of(browser, browsers.idleTimeout()))
                .orElseGet(() -> BrowserView.missing(browsers.idleTimeout()));
    }

    @PostMapping
    public BrowserView create() {
        return BrowserView.of(browsers.create(currentUser.require().id()), browsers.idleTimeout());
    }

    @PostMapping("/start")
    public BrowserView start() {
        return BrowserView.of(browsers.start(currentUser.require().id()), browsers.idleTimeout());
    }

    @PostMapping("/stop")
    public BrowserView stop() {
        return BrowserView.of(browsers.stop(currentUser.require().id()), browsers.idleTimeout());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        browsers.delete(currentUser.require().id());
        return ResponseEntity.noContent().build();
    }
}
