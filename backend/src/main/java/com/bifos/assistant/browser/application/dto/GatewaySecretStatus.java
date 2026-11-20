package com.bifos.assistant.browser.application.dto;

import java.time.Instant;

/**
 * 브라우저 중계 서명 비밀의 상태다. 원문은 담지 않는다.
 *
 * @param set 값이 있는가
 * @param prefix 화면에 보일 앞부분. 비었으면 null
 * @param updatedBy 마지막으로 바꾼 사용자 번호. 기동 때 옛 설정에서 가져왔거나 줄이 없으면 null
 * @param updatedAt 마지막으로 바꾼 시각. 줄이 없으면 null
 * @param gatewayUrlConfigured 중계 주소 설정이 있는가. 없으면 값이 있어도 중계는 꺼져 있다
 */
public record GatewaySecretStatus(boolean set, String prefix, Long updatedBy, Instant updatedAt, boolean gatewayUrlConfigured) {
}
