package com.bifos.assistant.orchestration.application.dto;

import java.util.List;

/**
 * 비서 대화의 {@code /} 목록에 보일, 맡길 수 있는 전문 에이전트 하나와 그 켜진 스킬이다(docs/features/assistant-hub/spec.md 의 REQ10).
 *
 * @param skills 커맨드로 부를 수 있는 스킬. 이름순이다
 */
public record SkillDelegationTarget(String agentCode, String agentName, List<Skill> skills) {

    public SkillDelegationTarget {
        skills = List.copyOf(skills);
    }


    /**
     * 맡길 스킬 하나다.
     *
     * @param name 스킬 이름
     * @param description {@code SKILL.md} 앞머리의 설명. 없으면 빈 글이다
     */
    public record Skill(String name, String description) {

        public Skill {
            description = description == null ? "" : description;
        }
    }
}
