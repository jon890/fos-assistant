package com.bifos.assistant.skill.application;

import com.bifos.assistant.skill.application.model.SkillPackageChange;

/**
 * 스킬 묶음 미리보기의 파일 한 줄이다.
 *
 * @param path 스킬 디렉터리 안의 상대 경로. {@code SKILL.md} 도 한 줄이다
 * @param size UTF-8 바이트. {@code REMOVED} 면 지금 스킬의 크기다
 */
public record SkillPackageFile(String path, long size, SkillPackageChange change) {}
