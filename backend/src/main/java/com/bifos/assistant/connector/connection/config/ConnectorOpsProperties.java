package com.bifos.assistant.connector.connection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 운영 정기 작업이 부르는 재시작 조회의 설정이다(ADR-20261120 / connector-auto-apply).
 *
 * @param token 운영 정기 작업이 {@code /internal/ops/connector-restart} 에 보내는 서비스 Bearer 토큰. 비어 있으면 조회는 늘 거절한다. 값과
 * 자리는 운영 저장소가 갖는다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.connector.ops")
public record ConnectorOpsProperties(String token) {

    /** 토큰 원문이 설정 출력과 로그에 남지 않게 가린다. */
    @Override
    public String toString() {
        return "ConnectorOpsProperties[token=" + (token == null || token.isBlank() ? "<empty>" : "<set>") + "]";
    }
}
