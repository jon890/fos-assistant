package com.bifos.assistant.skill.domain.type;

/** 스킬이 어떻게 쓰였는지다. */
public enum SkillUseSource {
    /** 사용자가 {@code /이름} 으로 불렀다. */
    COMMAND,
    /** 모델이 스스로 {@code skill_view} 로 읽었다. */
    MODEL
}
