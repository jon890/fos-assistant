package com.bifos.assistant.runtime.domain;

/**
 * 끝나기를 기다리는 실행이 어느 쪽 한도를 쓰는지다.
 *
 * <p>사람이 답을 기다리는 실행은 짧은 한도를, 사람이 화면 앞에서 기다리지 않는 실행은 긴 한도를 쓴다. 값은 구현이 설정에서 읽는다.
 * Hermes 는 {@code hermes.run-timeout} 과 {@code hermes.background-run-timeout} 이다. 어느 실행이 어느 쪽인지는
 * {@code docs/features/execution.md} 의 「실행 한도」 가 갖는다.
 */
public enum RunWait {
    /** 사람이 보낸 대화 turn 처럼 답을 기다리는 사람이 있는 실행이다. */
    CONVERSATION,

    /** 맡긴 자식 실행과 예약 작업이 연 turn 처럼 사람이 그 자리에서 기다리지 않는 실행이다. */
    BACKGROUND
}
