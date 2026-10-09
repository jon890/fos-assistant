package com.bifos.assistant.skill.application;

import java.util.List;

/**
 * 스킬 묶음을 저장하지 않고 판정한 결과다. 문제가 비어 있어야 올릴 수 있다.
 *
 * @param name 앞머리의 {@code name}. 앞머리를 읽지 못하면 {@code null}
 * @param description 앞머리의 {@code description}. 앞머리를 읽지 못하면 {@code null}
 * @param skillMdHead {@code SKILL.md} 앞 2,000자. 없으면 {@code null}
 * @param existing 같은 이름의 올린 스킬이 있다. 표식 없는 더 새 버전에만 있어도 참이다
 * @param baseDigest 지금 스킬의 지문. 새 스킬이면 {@code null}
 * @param hasScripts {@code scripts/} 아래 파일이 있다
 * @param files {@code SKILL.md} 가 첫 줄이고 나머지는 경로 순, 지금 스킬에만 있는 파일은 그 뒤에 경로 순이다
 * @param ignored 빼고 본 항목의 원래 경로
 * @param problems 검사의 문제 뒤에 에이전트에 따른 문제를 붙인 것. 50개까지다
 */
public record SkillPackagePreview(
        String name,
        String description,
        String skillMdHead,
        boolean existing,
        String baseDigest,
        boolean hasScripts,
        List<SkillPackageFile> files,
        List<String> ignored,
        List<SkillPackageProblem> problems) {

    public SkillPackagePreview {
        files = List.copyOf(files);
        ignored = List.copyOf(ignored);
        problems = List.copyOf(problems);
    }
}
