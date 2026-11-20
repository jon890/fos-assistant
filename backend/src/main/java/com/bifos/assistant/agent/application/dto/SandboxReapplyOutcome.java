package com.bifos.assistant.agent.application.dto;

/** 기동할 때 실행 공간 도구를 다시 저장해 본 결과다(ADR-20261120 / no-local-shell). */
public enum SandboxReapplyOutcome {
    /** 실행 공간 도구가 켜져 있지 않아 쓰지 않았다. */
    NOT_NEEDED,
    /** 지금 목록 그대로 실행 공간 쓰기로 다시 썼다. 남아 있던 local 설정은 docker 로 바뀌었다. */
    REWRITTEN,
    /** profile 이 실행 공간 정책에 없어 실행 공간 도구를 빼고 다시 썼다. */
    SHELL_REMOVED
}
