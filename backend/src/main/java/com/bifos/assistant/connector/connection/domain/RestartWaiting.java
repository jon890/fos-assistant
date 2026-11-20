package com.bifos.assistant.connector.connection.domain;

import java.time.Instant;

/**
 * 공유 gateway 재시작으로 풀릴 재시작 대기 바인딩 하나다(ADR-20261120 / connector-auto-apply).
 *
 * @param userId 연결 사용자 번호. 접근이 철회된 사용자의 대기는 재시작 감지가 풀지 못해 운영 조회가 뺀다
 * @param since {@code restart_required_since}
 */
public record RestartWaiting(Long userId, Instant since) {
}
