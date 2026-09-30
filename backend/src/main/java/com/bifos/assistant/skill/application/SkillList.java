package com.bifos.assistant.skill.application;

import java.util.List;

/**
 * 어느 에이전트의 스킬 목록이다.
 *
 * <p>{@code editable} 은 목록의 성질이 아니라 그것을 물어본 사람의 권한이다.
 *
 * @param skills Hermes 가 아는 스킬과 올린 스킬을 합친 목록. 이름 차례대로다
 * @param editable 물어본 사람이 스킬을 올리고 지울 수 있는가
 * @param skillsToolsetEnabled 그 에이전트의 API 실행에 {@code skills} toolset 이 켜져 있는가. 꺼져
 *     있으면 목록이 있어도 모델이 스킬을 읽지 못한다
 * @param uploadLimit 그 에이전트에 올릴 수 있는 스킬 수의 한도. 새 스킬을 만들 때만 본다
 */
public record SkillList(
        List<SkillListItem> skills, boolean editable, boolean skillsToolsetEnabled, int uploadLimit) {

    public SkillList {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
