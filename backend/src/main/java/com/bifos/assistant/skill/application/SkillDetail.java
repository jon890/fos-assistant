package com.bifos.assistant.skill.application;

import java.util.List;

/**
 * 올린 스킬 하나를 편집 화면이 받는 모양이다.
 *
 * @param name 스킬 이름
 * @param description {@code SKILL.md} 앞머리의 설명
 * @param body {@code SKILL.md} 원문 전체. 앞머리를 포함한다. 화면이 그대로 편집한다
 * @param files 참고 파일의 경로와 UTF-8 크기. 본문은 싣지 않는다
 */
public record SkillDetail(String name, String description, String body, List<SkillFileInfo> files) {

    public SkillDetail {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
