package com.bifos.assistant.skill.application;

import com.bifos.assistant.skill.domain.SkillFile;
import com.bifos.assistant.skill.infra.SkillFilePaths;
import java.util.List;

/**
 * 받은 스킬 묶음을 에이전트와 무관한 규칙으로 검사한 결과다. 문제가 없어야 올릴 수 있다.
 *
 * @param name 앞머리의 {@code name}. 앞머리를 읽지 못하면 {@code null}
 * @param description 앞머리의 {@code description}. 앞머리를 읽지 못하면 {@code null}
 * @param skillMd {@code SKILL.md} 원문. 없거나 글로 읽히지 않으면 {@code null}
 * @param files 경로와 글 검사를 통과한 참고 파일. 경로 차례대로이고 {@code SKILL.md} 는 빠진다
 * @param ignored 숨은 항목과 {@code __MACOSX} 아래 항목처럼 빼고 본 항목의 원래 경로
 * @param problems 찾은 문제. 받기의 문제면 그 하나뿐이고, 검사의 문제는 50개까지다
 */
public record CheckedSkillPackage(
        String name,
        String description,
        String skillMd,
        List<SkillFile> files,
        List<String> ignored,
        List<SkillPackageProblem> problems) {

    public CheckedSkillPackage {
        files = List.copyOf(files);
        ignored = List.copyOf(ignored);
        problems = List.copyOf(problems);
    }

    /** {@code scripts/} 아래 파일이 있는가. */
    public boolean hasScripts() {
        return files.stream().anyMatch(file -> SkillFilePaths.isScript(file.path()));
    }
}
