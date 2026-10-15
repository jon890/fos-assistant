package com.bifos.assistant.skill.domain;

/**
 * 스킬의 참고 파일 하나다.
 *
 * @param path 스킬 디렉터리 안의 상대 경로. {@code references/…} 나 {@code templates/…} 다
 * @param content 본문. 텍스트만 받는다
 */
public record SkillFile(String path, String content) {
}
