package com.bifos.assistant.skill.infra;

import com.bifos.assistant.skill.domain.SkillBundle;
import java.time.Instant;

/**
 * 스킬 하나의 이전 버전이다. 이미 있는 스킬을 저장할 때 바뀌기 전 것을 남긴다(ADR-20261009-skill-package).
 *
 * @param bundle 바뀌기 전 스킬 전체
 * @param savedAt 그 버전을 남긴 시각
 */
public record PreviousSkill(SkillBundle bundle, Instant savedAt) {}
