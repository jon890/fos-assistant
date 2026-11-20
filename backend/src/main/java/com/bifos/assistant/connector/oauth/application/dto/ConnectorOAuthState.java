package com.bifos.assistant.connector.oauth.application.dto;

import java.time.Instant;

/**
 * 모양만 읽은 상태 값이다. 서명은 그 흐름의 돌아오는 주소와 {@code nonce} 로 따로 확인한다.
 *
 * @param random 이 흐름을 가리키는 무작위 값
 */
public record ConnectorOAuthState(long userId, String connectorId, Instant expiresAt, String random, String signature) {
}
