package com.bifos.assistant.usage.execution.domain;

import java.time.Instant;

/**
 * 실패한 루트 실행 한 줄이다. 관리자 요약의 최근 실패 목록을 만든다.
 *
 * <p>실패의 기준은 웹이 실행 기록에 실패 배지를 붙이는 규칙과 같다. 도는 중이 아니고, 상태가 실패이거나 오류 코드가 있다.
 *
 * @param id 실행 번호
 * @param userId 실행한 사용자 번호
 * @param agentName 에이전트 이름. 에이전트가 지워졌으면 null
 * @param startedAt 시작 시각
 * @param errorCode 오류 코드. 상태만 실패이고 코드가 없으면 null
 */
public record FailedExecutionRow(Long id, Long userId, String agentName, Instant startedAt, String errorCode) {
}
