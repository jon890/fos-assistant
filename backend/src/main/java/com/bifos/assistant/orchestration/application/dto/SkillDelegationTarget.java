package com.bifos.assistant.orchestration.application.dto;

import java.util.List;

/**
 * 비서 대화의 {@code /} 목록에 보일, 맡길 수 있는 전문 에이전트 하나와 그 켜진 스킬이다(docs/features/assistant-hub/spec.md 의 REQ10).
 *
 * @param skills 커맨드로 부를 수 있는 스킬 이름. 이름순이다
 */
public record SkillDelegationTarget(String agentCode, String agentName, List<String> skills) {

    public SkillDelegationTarget {
        skills = List.copyOf(skills);
    }
}
