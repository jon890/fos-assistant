package com.bifos.assistant.notification.infra;

import java.time.Duration;

/**
 * 푸시 서비스의 응답이다(ADR-20261120 / web-push).
 *
 * @param status HTTP 상태 코드. 연결하지 못했거나 제한 시간이 지났으면 0
 * @param retryAfter {@code Retry-After} 의 기다림. 없거나 읽지 못하면 null
 */
public record PushResponse(int status, Duration retryAfter) {

    public static PushResponse unreachable() {
        return new PushResponse(0, null);
    }


    public boolean delivered() {
        return status >= 200 && status < 300;
    }


    /** 그 구독이 더 없다는 답인가. */
    public boolean gone() {
        return status == 404 || status == 410;
    }


    /** 다시 보내 볼 만한 일시 실패인가. */
    public boolean transientFailure() {
        return status == 0 || status == 429 || status >= 500;
    }
}
