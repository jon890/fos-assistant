package com.bifos.assistant.agent.application.dto;

/** 기동할 때 실행 공간 도구를 다시 저장해 본 결과다(ADR-20261120 / no-local-shell). */
public enum SandboxReapplyOutcome {
    /** 실행 공간 도구가 켜져 있지 않아 쓰지 않았다. */
    NOT_NEEDED,
    /** 지금 목록 그대로 실행 공간 쓰기로 다시 썼다. 남아 있던 local 설정은 docker 로 바뀌었다. */
    REWRITTEN,
    /**
     * 실행 공간 도구를 빼고 다시 썼다. profile 이 실행 공간 정책에 없거나, 주인이 있는 그룹 공개 에이전트라 실행 공간이 주인 공간인 경우다.
     * 배포 때 한 번 점검하는 에이전트에서만 생긴다.
     */
    SHELL_REMOVED,
    /**
     * profile 이 실행 공간 정책에 없지만 이미 점검을 끝낸 에이전트라 아무것도 바꾸지 않았다. 운영이 정책에서 profile 을 뺐거나 정책을
     * 잘못 고친 경우다. 셸 저장은 plugin 이 계속 거절하므로 local 로 열리지 않는다.
     */
    UNLISTED_KEPT
}
