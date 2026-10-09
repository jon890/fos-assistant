package com.bifos.assistant.skill.application;

import static com.bifos.assistant.skill.application.SkillInputRules.utf8Bytes;
import static com.bifos.assistant.skill.infra.SkillFilePaths.SKILL_MD;

import com.bifos.assistant.skill.application.model.SkillPackageChange;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 스킬 묶음 미리보기의 파일 목록과 {@code SKILL.md} 앞부분을 만든다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SkillPackageDiff {

    private static final int HEAD_CHARS = 2_000;

    /**
     * 묶음의 파일마다 지금 스킬과 견준 바뀜을 붙인다. {@code skillMd} 가 {@code null} 이면 빈 목록이다.
     *
     * @param files 경로 차례대로인 참고 파일
     * @param current 같은 이름의 지금 스킬. 없으면 {@code null}
     */
    static List<SkillPackageFile> files(String skillMd, List<SkillFile> files, SkillBundle current) {
        if (skillMd == null) {
            return List.of();
        }
        Map<String, String> before = new TreeMap<>();
        if (current != null) {
            before.put(SKILL_MD, current.skillMd());
            current.files().forEach(file -> before.put(file.path(), file.content()));
        }
        List<SkillFile> incoming = new ArrayList<>(files.size() + 1);
        incoming.add(new SkillFile(SKILL_MD, skillMd));
        incoming.addAll(files);
        List<SkillPackageFile> result = new ArrayList<>();
        for (SkillFile file : incoming) {
            String old = before.remove(file.path());
            SkillPackageChange change = old == null
                    ? SkillPackageChange.ADDED
                    : old.equals(file.content()) ? SkillPackageChange.SAME : SkillPackageChange.CHANGED;
            result.add(new SkillPackageFile(file.path(), utf8Bytes(file.content()), change));
        }
        before.forEach((path, content) ->
                result.add(new SkillPackageFile(path, utf8Bytes(content), SkillPackageChange.REMOVED)));
        return result;
    }

    /** 앞 2,000자다. 끝 글자가 surrogate 쌍의 앞쪽이면 1,999자로 자른다. */
    static String head(String skillMd) {
        if (skillMd == null || skillMd.length() <= HEAD_CHARS) {
            return skillMd;
        }
        int end = Character.isHighSurrogate(skillMd.charAt(HEAD_CHARS - 1)) ? HEAD_CHARS - 1 : HEAD_CHARS;
        return skillMd.substring(0, end);
    }
}
