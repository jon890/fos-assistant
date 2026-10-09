package com.bifos.assistant.skill.application;

import java.time.Instant;
import java.util.List;

/**
 * 올린 스킬 하나를 편집 화면이 받는 모양이다.
 *
 * @param name 스킬 이름
 * @param description {@code SKILL.md} 앞머리의 설명
 * @param body {@code SKILL.md} 원문 전체. 앞머리를 포함한다. 화면이 그대로 편집한다
 * @param files 참고 파일의 경로와 UTF-8 크기와 편집할 원문
 * @param previousSavedAt 이전 버전을 남긴 시각. 이전 버전이 없으면 {@code null} 이다
 */
public record SkillDetail(
        String name, String description, String body, List<SkillFileInfo> files, Instant previousSavedAt) {

    public SkillDetail {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
