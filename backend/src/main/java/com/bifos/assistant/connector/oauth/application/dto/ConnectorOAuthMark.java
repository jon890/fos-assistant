package com.bifos.assistant.connector.oauth.application.dto;

/**
 * 확인한 토큰 통로 표식이다.
 *
 * @param nonce 대기 표식이 가리키는 흐름. 본 연결 표식이면 null
 */
public record ConnectorOAuthMark(long connectionId, String nonce) {
}
