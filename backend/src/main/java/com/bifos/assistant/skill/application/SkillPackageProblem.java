package com.bifos.assistant.skill.application;

import com.bifos.assistant.skill.application.model.SkillPackageReason;

/**
 * 스킬 묶음의 문제 하나다.
 *
 * <p>경로는 올린 사람이 정한 글이라 응답과 오류 메시지에 그대로 실리지 않게 {@link #MAX_PATH_CHARS} 자로 자른다. 대리 쌍의
 * 앞 반쪽에서 끊기면 그 한 글자를 더 뺀다.
 *
 * @param reason 까닭
 * @param path 문제가 난 항목의 경로. 묶음 전체의 문제면 {@code null}
 */
public record SkillPackageProblem(SkillPackageReason reason, String path) {

    public static final int MAX_PATH_CHARS = 200;

    public SkillPackageProblem {
        if (path != null && path.length() > MAX_PATH_CHARS) {
            int end = Character.isHighSurrogate(path.charAt(MAX_PATH_CHARS - 1)) ? MAX_PATH_CHARS - 1 : MAX_PATH_CHARS;
            path = path.substring(0, end);
        }
    }
}
