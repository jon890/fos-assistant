package com.bifos.assistant.skill.library.application.dto;

import com.bifos.assistant.hermes.application.port.HermesSkillClient.HermesSkill;
import com.bifos.assistant.skill.usage.application.dto.SkillUsageSummary;

/**
 * 스킬 목록의 한 줄이다.
 *
 * @param name 스킬 이름
 * @param description {@code SKILL.md} 앞머리의 설명
 * @param source 스킬의 설치 출처
 * @param connectorTitle 플러그인 이름. 플러그인이 설치한 스킬에만 있다
 * @param enabled 대시보드의 전역 켜고 끄기 상태
 * @param usage 호출 수와 마지막 호출 시각. 편집자에게만 채우고 그 밖에는 {@code null} 이다
 */
public record SkillListItem(
    String name, String description, SkillSource source, boolean enabled, SkillUsageSummary usage,
    String connectorTitle) {
    public static SkillListItem fromHermes(HermesSkill skill, boolean uploaded, SkillUsageSummary usage) {
        String connectorTitle = uploaded ? null : skill.connectorTitle();
        SkillSource source = uploaded ? SkillSource.UPLOADED : SkillSource.HERMES;
        if (connectorTitle != null) {
            source = SkillSource.CONNECTOR;
        }
        return new SkillListItem(skill.name(), skill.description(), source, skill.enabled(), usage, connectorTitle);
    }


    public SkillListItem(String name, String description, SkillSource source, boolean enabled, SkillUsageSummary usage) {
        this(name, description, source, enabled, usage, null);
    }
}
