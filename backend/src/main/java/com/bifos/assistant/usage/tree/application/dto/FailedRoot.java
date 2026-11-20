package com.bifos.assistant.usage.tree.application.dto;

import java.time.Instant;

/**
 * 실패한 루트 실행 한 줄이다.
 *
 * @param id 실행 번호
 * @param userId 실행한 사용자 번호
 * @param agentName 에이전트 이름. 에이전트가 지워졌으면 null
 * @param startedAt 시작 시각
 * @param errorCode 오류 코드. 없으면 null
 */
public record FailedRoot(Long id, Long userId, String agentName, Instant startedAt, String errorCode) {
}
