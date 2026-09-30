package com.bifos.assistant.skill.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자 하나가 에이전트 하나에서 스킬 하나를 부른 합계다. 그 사용자 자신이 본다.
 *
 * @param agentCode 에이전트 code
 * @param agentName 에이전트 이름
 * @param skillName 스킬 이름
 * @param count 호출 수
 * @param lastInvokedAt 마지막 호출 시각
 * @param lastConversationId 마지막 호출이 속한 대화의 공개 식별자. 그 대화를 지웠거나 대화 없이 돈 실행이면
 *     {@code null}
 */
public record UserSkillUsage(
        String agentCode,
        String agentName,
        String skillName,
        long count,
        Instant lastInvokedAt,
        UUID lastConversationId) {
}
