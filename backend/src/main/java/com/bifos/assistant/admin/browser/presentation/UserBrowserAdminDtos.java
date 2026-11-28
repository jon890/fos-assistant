package com.bifos.assistant.admin.browser.presentation;

import com.bifos.assistant.browser.application.dto.UserBrowserSnapshot;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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

    /** 그룹 전체의 에이전트 브라우저 조작 스위치다. 거짓이면 켜 둔 사용자도 쓰지 않는다. */
    public record AdminAgentControlView(boolean enabled) {
    }

    /** 그룹 전체 스위치를 바꾸는 본문이다. */
    public record AdminAgentControlRequest(Boolean enabled) {

        /** 칸이 없으면 {@code VALIDATION_FAILED} 다. */
        static boolean required(AdminAgentControlRequest body) {
            if (body == null || body.enabled() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "enabled is required");
            }
            return body.enabled();
        }
    }
}
