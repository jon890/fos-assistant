package com.bifos.assistant.browser.presentation;

import com.bifos.assistant.shared.error.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/** 브라우저 중계의 판정이 던진 오류 코드를 응답 상태로 바꾼다. HTTP 창구와 WebSocket handshake 가 함께 쓴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BrowserGatewayStatus {

    static HttpStatus of(ErrorCode code) {
        return switch (code) {
            case BROWSER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BROWSER_DISABLED, BROWSER_CAPACITY, BROWSER_BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            // BROWSER_START_FAILED, BROWSER_STOP_FAILED 와 그 밖의 코드는 브라우저 쪽 실패다
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}
