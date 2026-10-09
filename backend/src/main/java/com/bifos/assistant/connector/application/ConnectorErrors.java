package com.bifos.assistant.connector.application;

import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 커넥터 연결이 돌려주는 고정 오류다.
 *
 * <p>메시지는 고정 문장이다. 칸 이름, 칸 값, 원격 응답을 싣지 않는다. 공통 어휘와 오류 코드의 대응은
 * {@code docs/prd.md} 의 표와 같다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorErrors {

    /** 커넥터 도구가 돌려준 공통 어휘를 오류 코드로 바꾼다. */
    static ApiException of(ConnectorCallError error) {
        return switch (error) {
            case CREDENTIAL_REJECTED ->
                new ApiException(ErrorCode.CONNECTOR_CREDENTIAL_REJECTED, "the connector rejected the given values");
            case FORBIDDEN -> new ApiException(ErrorCode.CONNECTOR_FORBIDDEN, "the given values are not allowed");
            case INVALID_INPUT -> invalid();
            case UNAVAILABLE -> unavailable();
        };
    }

    static ApiException notFound() {
        return new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "no such connector");
    }

    static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "connector values are invalid");
    }

    static ApiException unavailable() {
        return new ApiException(ErrorCode.CONNECTOR_UNAVAILABLE, "the connector is unavailable");
    }
}
