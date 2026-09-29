package com.bifos.assistant.skill.application;

import java.util.List;

/**
 * 스킬 하나의 파일 전체다. 버전 디렉터리의 스킬 디렉터리 하나와 같다.
 *
 * @param name 스킬 이름. 디렉터리 이름이자 {@code SKILL.md} 앞머리의 {@code name} 이다
 * @param skillMd {@code SKILL.md} 원문 전체. 앞머리를 포함한다
 * @param files 참고 파일. 경로 차례대로다. 없으면 빈 목록
 */
public record SkillBundle(String name, String skillMd, List<SkillFile> files) {

    public SkillBundle {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
