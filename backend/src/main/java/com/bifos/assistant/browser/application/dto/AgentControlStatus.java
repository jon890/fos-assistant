package com.bifos.assistant.browser.application.dto;

/**
 * 에이전트가 그 사용자의 「내 브라우저」 를 쓸 수 있는지의 판정이다(docs/features/agent-browser/design.md 의 「PR1 스위치」).
 *
 * <p>앞에서부터 본다. 기능이 꺼졌으면 다른 칸을 보지 않는다.
 */
public enum AgentControlStatus {
    /** 쓸 수 있다. */
    ALLOWED,
    /** 브라우저 기능이 꺼져 있다. */
    FEATURE_OFF,
    /** 관리자가 그룹 전체를 꺼 두었다. */
    ADMIN_OFF,
    /** 그 사용자의 브라우저가 없다. */
    NO_BROWSER,
    /** 사용자가 켜지 않았다. */
    USER_OFF;


    public boolean allowed() {
        return this == ALLOWED;
    }
}
