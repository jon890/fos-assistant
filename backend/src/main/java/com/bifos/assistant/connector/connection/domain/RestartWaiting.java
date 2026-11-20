package com.bifos.assistant.connector.connection.domain;

import java.time.Instant;

/**
 * 공유 gateway 재시작으로 풀릴 재시작 대기 바인딩의 수와 가장 오래된 대기 시각이다(ADR-20261120 / connector-auto-apply).
 *
 * @param count 바인딩 수
 * @param oldestSince 가장 오래된 {@code restart_required_since}. 없으면 null
 */
public record RestartWaiting(long count, Instant oldestSince) {
}
