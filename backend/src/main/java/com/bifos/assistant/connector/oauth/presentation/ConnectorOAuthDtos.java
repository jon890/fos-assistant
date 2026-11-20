package com.bifos.assistant.connector.oauth.presentation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 단추 연결 경로의 요청과 응답 모양이다. 토큰과 코드가 든 타입은 문자열 표현에 값을 쓰지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorOAuthDtos {

    /** @param expiresAt 만료 시각의 epoch 초. 만료가 없으면 null */
    public record TokenView(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_at") Long expiresAt) {
        @Override
        public String toString() {
            return "TokenView[값 생략]";
        }
    }
}
