package com.bifos.assistant.skill.application;

/**
 * 스킬 목록의 한 줄이다.
 *
 * @param name 스킬 이름
 * @param description {@code SKILL.md} 앞머리의 설명
 * @param source 지금 버전 디렉터리에 있는 이름이면 {@link SkillSource#UPLOADED}, 아니면 {@link
 *     SkillSource#HERMES}
 * @param enabled 대시보드의 전역 켜고 끄기 상태
 * @param usage 호출 수와 마지막 호출 시각. 아직 채우지 않아 {@code null} 이다
 */
public record SkillListItem(
        String name, String description, SkillSource source, boolean enabled, SkillUsageSummary usage) {
}
