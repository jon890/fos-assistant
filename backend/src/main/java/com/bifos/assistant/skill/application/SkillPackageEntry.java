package com.bifos.assistant.skill.application;

/**
 * 받은 스킬 묶음의 파일 하나다.
 *
 * @param path zip 안의 원래 경로. 정규화하지 않는다
 * @param content 실제로 푼 바이트
 */
public record SkillPackageEntry(String path, byte[] content) {}
