package com.bifos.assistant.browser.application.model;

/**
 * 확인을 지난 중계 접근 표식이 허락하는 것이다.
 *
 * @param kind 표식의 종류
 * @param id {@link Kind#BINDING} 이면 바인딩 번호, {@link Kind#CALL} 이면 사용자 번호다
 */
public record BrowserGrant(Kind kind, long id) {

    /** 표식의 종류다. */
    public enum Kind {
        /** 바인딩 표식이다. 그 바인딩의 연결 주인의 브라우저를 쓴다. */
        BINDING,
        /** 호출 표식이다. 그 사용자의 브라우저를 쓴다. 만료가 있다. */
        CALL
    }
}
