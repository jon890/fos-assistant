package com.bifos.assistant.skill.application;

import com.bifos.assistant.skill.application.model.SkillPackageReason;

/**
 * 스킬 묶음의 문제 하나다.
 *
 * @param reason 까닭
 * @param path 문제가 난 항목의 경로. 묶음 전체의 문제면 {@code null}
 */
public record SkillPackageProblem(SkillPackageReason reason, String path) {}
