package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.browser.application.model.UserBrowserSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 사용자 브라우저 경로의 응답 모양이다. 계약은 {@code docs/backend/user-browser.md} 의 「API」 가 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserBrowserDtos {

    /**
     * 내 브라우저다. 기능이 꺼져 있으면 {@code enabled} 만 싣고, 브라우저가 없으면 {@code exists} 가 거짓이다.
     *
     * @param idleTimeoutSeconds 자동 중지까지의 유휴 시간(초). 화면이 안내 문구에 쓴다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserView(
            boolean enabled,
            Boolean exists,
            String status,
            @JsonInclude(JsonInclude.Include.ALWAYS) String lastError,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant startedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastActiveAt,
            Long idleTimeoutSeconds) {

        static BrowserView disabled() {
            return new BrowserView(false, null, null, null, null, null, null);
        }

        static BrowserView missing(Duration idleTimeout) {
            return new BrowserView(true, false, null, null, null, null, idleTimeout.toSeconds());
        }

        static BrowserView of(UserBrowserSnapshot browser, Duration idleTimeout) {
            return new BrowserView(
                    true,
                    true,
                    browser.status().name(),
                    browser.lastError(),
                    browser.startedAt(),
                    browser.lastActiveAt(),
                    idleTimeout.toSeconds());
        }
    }

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
