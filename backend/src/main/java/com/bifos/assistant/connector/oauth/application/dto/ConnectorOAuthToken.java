package com.bifos.assistant.connector.oauth.application.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * 메모리에만 두는 access token 이다. DB 와 로그에 쓰지 않는다.
 *
 * @param expiresAt 만료 시각. 제공자가 만료를 주지 않았으면 null
 */
public record ConnectorOAuthToken(String accessToken, Instant expiresAt) {

    /** 지금부터 그 시간이 지나도 쓸 수 있는가. */
    public boolean validFor(Duration ahead, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now.plus(ahead));
    }


    @Override
    public String toString() {
        return "ConnectorOAuthToken[값 생략]";
    }
}
