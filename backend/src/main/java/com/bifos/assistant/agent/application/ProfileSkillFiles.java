package com.bifos.assistant.agent.application;

/** profile 에 올라간 스킬 파일을 묻고 지운다. 구현은 스킬 쪽이 갖는다. */
public interface ProfileSkillFiles {

    /** 그 profile 에 올린 스킬이 하나라도 있는지 본다. */
    boolean hasUploaded(String profile);

    /** 그 profile 에 올린 스킬을 모두 지운다. */
    void deleteAll(String profile);
}
