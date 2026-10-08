package com.bifos.assistant.browser.domain;

/**
 * CDP HTTP 창구가 돌려준 응답이다. 중계가 커넥터에 넘길 때 쓴다.
 *
 * @param status Chrome 이 준 상태 코드
 * @param contentType Chrome 이 준 {@code Content-Type}. 없으면 {@code null}
 * @param body 응답 본문. 1MB 까지다
 */
public record CdpReply(int status, String contentType, byte[] body) {}
