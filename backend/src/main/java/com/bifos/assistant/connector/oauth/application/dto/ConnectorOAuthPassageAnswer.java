package com.bifos.assistant.connector.oauth.application.dto;

/**
 * 토큰 통로의 답이다.
 *
 * @param status 토큰을 주지 못한 HTTP 상태 코드. 토큰이 있으면 200
 * @param token 줄 토큰. 주지 못하면 null
 */
public record ConnectorOAuthPassageAnswer(int status, ConnectorOAuthToken token) {
}
