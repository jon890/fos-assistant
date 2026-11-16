package com.bifos.assistant.browser.application.port;

/**
 * 그 사용자의 브라우저를 쓰는 승인한 실행(주문서 채우기, 좌석 확보)이 도는 중인지 답한다. 사람이 브라우저를 넘겨받기 전에 묻는다.
 *
 * <p>뜻은 {@code docs/features/browser-cowork/spec.md} 의 REQ6 이 갖는다. 승인 줄은 {@code connector} 가 갖고 {@code browser} 가 그
 * 패키지보다 아래라 이 인터페이스를 두고 {@code connector} 가 구현한다.
 */
public interface BrowserApprovedRuns {

    /** 브라우저를 쓰는 승인한 실행이 도는 중인가. 모르면 참이다. */
    boolean running(long userId);
}
