package com.bifos.assistant.skill.domain;

import java.time.Instant;

/**
 * 사용자 하나의 스킬 사용 한 건에 그 실행의 에이전트와 대화를 이어 붙인 것이다.
 *
 * <p>사용자별 합계는 마지막 호출이 속한 대화까지 내야 해서 데이터베이스에서 묶지 않고 이 줄을 읽어 모은다.
 *
 * @param executionId 그 사용이 적힌 실행. 같은 실행의 {@code COMMAND} 와 {@code MODEL} 줄을 한 번으로 세는 데 쓴다
 * @param agentId 그 실행의 에이전트
 * @param conversationId 그 실행이 속한 대화. 대화 없이 돈 실행이면 {@code null}
 * @param skillName 스킬 이름
 * @param occurredAt 쓰인 시각
 */
public record SkillUseOccurrence(
        Long executionId, Long agentId, Long conversationId, String skillName, Instant occurredAt) {
}
