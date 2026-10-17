package com.bifos.assistant.skill.application;

import com.bifos.assistant.skill.application.model.SkillPackageReason;
import java.util.List;

/**
 * 스킬 묶음을 받은 결과다. 받기는 처음 걸리는 문제 하나로 끝나므로 문제는 많아야 하나다.
 *
 * @param entries 디렉터리를 뺀 파일 목록. 실패면 빈 목록이다
 * @param problem 실패한 까닭. 성공이면 {@code null}
 */
public record ReceivedSkillPackage(List<SkillPackageEntry> entries, SkillPackageProblem problem) {

    /** 문제 하나로 끝난 받기다. */
    public static ReceivedSkillPackage failed(SkillPackageReason reason, String path) {
        return new ReceivedSkillPackage(List.of(), new SkillPackageProblem(reason, path));
    }
}
