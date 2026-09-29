package com.bifos.assistant.skill.application;

/**
 * 스킬을 저장할 때 받는 참고 파일 하나다.
 *
 * @param path 스킬 디렉터리 안의 상대 경로
 * @param content 본문. {@code null} 이면 지금 버전의 같은 스킬의 같은 경로 내용을 그대로 쓴다. 화면이
 *     고치지 않은 파일을 다시 보내지 않기 위해서다
 */
public record SkillFileInput(String path, String content) {
}
