package com.bifos.assistant.proactive.application.model;

/** 살펴보기를 시작하지 못하게 막는 까닭이다. 뜻은 {@code docs/backend/proactive-check.md} 의 「시작 전 점검」 이 갖는다. */
public enum CheckBlockerCode {
    /** 설정으로 꺼 두었다. */
    DISABLED,
    /** 커넥터 에이전트이거나 흐름이 붙은 에이전트다. */
    AGENT_NOT_SUPPORTED,
    /** 켜진 스킬에 분야 지침 {@code proactive-check} 가 없다. */
    SKILL_MISSING,
    /** 켜진 toolset 에 허용 목록 밖의 것이 있다. */
    TOOLSETS_NOT_ALLOWED,
    /** 사용자별 격리 실행 공간이 없는 동안 셸, 파일, 코드 실행 도구가 켜져 있다. */
    ISOLATED_EXECUTION_REQUIRED
}
