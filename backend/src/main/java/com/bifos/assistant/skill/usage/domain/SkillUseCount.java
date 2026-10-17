package com.bifos.assistant.skill.domain;

import java.time.Instant;

/**
 * 에이전트 하나 안에서 스킬 이름별로 묶은 합계다. 질의가 데이터베이스에서 묶어 이 모양으로 낸다.
 *
 * @param skillName 스킬 이름
 * @param count 그 스킬이 쓰인 실행 수
 * @param lastInvokedAt 마지막으로 쓰인 시각
 */
public record SkillUseCount(String skillName, long count, Instant lastInvokedAt) {
}
