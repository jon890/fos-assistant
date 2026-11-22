package com.bifos.assistant.connector.oauth.presentation;

import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthCompletion;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
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

    /**
     * 단추 연결의 시작 요청이다.
     *
     * @param redirectUri 웹이 자기 공개 주소로 만든 돌아오는 주소
     * @param values 단추 밖 칸 값. 비밀 원문이 있을 수 있어 문자열 표현에 쓰지 않는다
     */
    public record StartRequest(String redirectUri, Map<String, String> values) {
        @Override
        public String toString() {
            return "StartRequest[값 생략]";
        }
    }

    /** @param authorizeUrl 사용자를 보낼 서비스의 로그인 주소 */
    public record StartView(String authorizeUrl) {
    }

    /** 서비스 화면에서 돌아온 주소의 query 를 웹이 그대로 옮긴 것이다. */
    public record CompleteRequest(String state, String code, String error, String setupAction) {
        @Override
        public String toString() {
            return "CompleteRequest[값 생략]";
        }
    }

    /** 결과 종류는 enum 이름 그대로 낸다. 칸의 뜻은 {@link ConnectorOAuthCompletion} 과 같다. */
    public record CompletionView(String outcome, String connectorId, String authorizeUrl, String reason) {
        static CompletionView from(ConnectorOAuthCompletion value) {
            return new CompletionView(value.outcome().name(), value.connectorId(), value.authorizeUrl(), value.reason());
        }
    }
}
