package com.bifos.assistant.proactive.application.model;

/** 살펴보기를 시작하지 못하게 막는 까닭이다. 보는 순서와 허용 toolset 목록은 {@code ProactiveCheckReadiness} 가 갖는다. */
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
    ISOLATED_EXECUTION_REQUIRED,
    /** 준비 상태를 조회하지 않았거나 Hermes 장애로 확인하지 못했다. */
    READINESS_UNKNOWN
}
