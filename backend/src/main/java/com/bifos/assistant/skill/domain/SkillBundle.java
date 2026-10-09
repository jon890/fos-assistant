package com.bifos.assistant.skill.domain;

import com.bifos.assistant.shared.util.Sha256;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 스킬 하나의 파일 전체다. 버전 디렉터리의 스킬 디렉터리 하나와 같다.
 *
 * @param name 스킬 이름. 디렉터리 이름이자 {@code SKILL.md} 앞머리의 {@code name} 이다
 * @param skillMd {@code SKILL.md} 원문 전체. 앞머리를 포함한다
 * @param files 참고 파일. 경로 차례대로다. 없으면 빈 목록
 */
public record SkillBundle(String name, String skillMd, List<SkillFile> files) {

    private static final String SKILL_MD_PATH = "SKILL.md";
    private static final char NUL = '\u0000';

    public SkillBundle {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /**
     * 이 스킬의 지문이다. 미리보기가 준 지문과 올릴 때의 지금 스킬을 견주어 그 사이에 바뀌었는지 본다.
     *
     * <p>{@code SKILL.md} 를 경로 {@code SKILL.md} 인 파일로 넣고, 모든 파일을 경로 차례로 {@code 경로 NUL 내용 NUL} 로
     * 이은 글의 UTF-8 SHA-256 을 소문자 16진수 64자로 적는다. 파일을 받은 차례와 관계없이 같다.
     */
    public String digest() {
        List<SkillFile> all = new ArrayList<>(files.size() + 1);
        all.add(new SkillFile(SKILL_MD_PATH, skillMd));
        all.addAll(files);
        all.sort(Comparator.comparing(SkillFile::path));
        StringBuilder joined = new StringBuilder();
        for (SkillFile file : all) {
            joined.append(file.path()).append(NUL).append(file.content()).append(NUL);
        }
        return Sha256.hex(joined.toString());
    }
}
