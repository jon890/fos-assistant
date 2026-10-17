package com.bifos.assistant.skill.application.model;

/** 스킬 묶음 미리보기에서 파일 하나가 지금 스킬과 견줘 어떻게 바뀌는가다. 저장하지 않는다. */
public enum SkillPackageChange {
    /** 지금 스킬에 없다. 새 스킬이면 모든 파일이 이것이다. */
    ADDED,
    /** 지금 스킬에 있고 내용이 다르다. */
    CHANGED,
    /** 지금 스킬과 내용이 같다. */
    SAME,
    /** 지금 스킬에만 있다. 올리면 없어진다. */
    REMOVED
}
