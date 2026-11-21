package com.bifos.assistant.connector.oauth.application.dto;

/**
 * 서명 비밀을 새로 만든 뒤 단추 연결의 통로 주소를 다시 쓴 결과다.
 *
 * @param total 다시 쓰려 한 단추 연결 수
 * @param failed 그 가운데 쓰지 못한 수. 그 연결의 플러그인은 토큰을 받지 못하므로 새로 만들기를 다시 하거나 그 연결을 다시 연결한다
 */
public record ConnectorOAuthSigningRewrite(int total, int failed) {
}
