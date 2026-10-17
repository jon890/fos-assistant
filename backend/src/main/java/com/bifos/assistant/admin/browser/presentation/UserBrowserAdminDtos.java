package com.bifos.assistant.admin.browser.presentation;

import com.bifos.assistant.browser.application.model.UserBrowserSnapshot;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 브라우저 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserBrowserAdminDtos {

    /** 관리자 목록의 한 줄이다. 컨테이너 번호와 프로필 키는 싣지 않는다. */
    public record AdminBrowserView(
        Long id,
        Long userId,
        String userName,
        String status,
        String lastError,
        Instant startedAt,
        Instant lastActiveAt) {

        static AdminBrowserView of(UserBrowserSnapshot browser, String userName) {
            return new AdminBrowserView(
                browser.id(),
                browser.userId(),
                userName,
                browser.status().name(),
                browser.lastError(),
                browser.startedAt(),
                browser.lastActiveAt());
        }
    }
}
