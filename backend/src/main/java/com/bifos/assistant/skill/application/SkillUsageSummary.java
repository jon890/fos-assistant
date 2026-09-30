package com.bifos.assistant.skill.application;

import java.time.Instant;

/**
 * 스킬 하나의 호출 합계다. 에이전트를 관리하는 사람이 본다.
 *
 * @param count 호출 수
 * @param lastInvokedAt 마지막 호출 시각. 호출이 없으면 {@code null}
 */
public record SkillUsageSummary(long count, Instant lastInvokedAt) {
}
