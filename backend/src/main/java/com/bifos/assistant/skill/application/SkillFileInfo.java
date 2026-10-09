package com.bifos.assistant.skill.application;

/**
 * 참고 파일의 경로와 크기와 편집할 원문이다.
 *
 * @param path 스킬 디렉터리 안의 상대 경로
 * @param size UTF-8 바이트 수
 * @param content 파일 원문
 */
public record SkillFileInfo(String path, long size, String content) {}
