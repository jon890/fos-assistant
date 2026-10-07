package com.bifos.assistant.browser.application.model;

import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import java.time.Instant;

/** 화면과 관리자 목록에 보이는 브라우저 한 줄이다. 컨테이너 번호와 프로필 키는 싣지 않는다. */
public record UserBrowserSnapshot(
        Long id, Long userId, UserBrowserStatus status, String lastError, Instant startedAt, Instant lastActiveAt) {

    public static UserBrowserSnapshot of(UserBrowser browser) {
        return new UserBrowserSnapshot(
                browser.id(),
                browser.userId(),
                browser.status(),
                browser.lastError(),
                browser.startedAt(),
                browser.lastActiveAt());
    }
}
