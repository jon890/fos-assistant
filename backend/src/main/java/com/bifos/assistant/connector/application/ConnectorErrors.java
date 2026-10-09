package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 커넥터 연결이 돌려주는 고정 오류다.
 *
 * <p>메시지는 고정 문장이다. 칸 이름, 칸 값, 원격 응답을 싣지 않는다. 공통 어휘와 오류 코드의 대응은
 * {@code docs/connectors.md} 의 표와 같다.
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

    /** 관리자 반영 완료가 {@code READY} 를 확인하지 못한 까닭을 오류로 바꾼다. {@code READY} 는 오류가 아니어서 받지 않는다. */
    static ApiException notApplied(ResyncOutcome outcome) {
        return switch (outcome) {
            case CALL_FAILED -> new ConnectorOperationFailure();
            case NOT_INSTALLED, POLICY_HOOK_OFF ->
                new ApiException(ErrorCode.CONNECTOR_INSTALL_MISMATCH, "the installed connector does not match");
            case PROBE_FAILED, NO_TOOLS, TOOLSETS_DIFFER ->
                new ApiException(ErrorCode.CONNECTOR_TOOLS_UNVERIFIED, "the connector tools could not be verified");
            case APPLY_SCHEDULED ->
                new ApiException(ErrorCode.CONNECTOR_APPLY_SCHEDULED, "the connector is still being applied");
            case RESTART_PENDING ->
                new ApiException(ErrorCode.CONNECTOR_RESTART_AGAIN, "the gateway restart is still pending");
            case CATALOG_MISSING -> notFound();
            case READY -> throw new IllegalStateException("a ready binding has no failure");
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
