package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.CheckAnswer;

/**
 * 먼저 살펴보기 turn 하나에서 살펴보기만의 일을 맡는 port 다(ADR-080).
 *
 * <p>{@code chat} 은 {@code proactive} 를 import 하지 않는다. {@code proactive} 가 이 인터페이스를 구현해
 * {@link ChatService#runProactiveCheck} 에 넘긴다. 살펴보기 한 번마다 새 구현을 만든다.
 */
public interface CheckTurn {

    /** Hermes 에 보내는 {@code instructions} 끝에 붙일 Control Plane 지시다. */
    String instructions();

    /** Hermes 입력이다. 처음 부를 때 만들고 다시 불리면 같은 값을 돌려준다. */
    String input();

    /** turn 을 시작할 때 남기는 알림 줄의 글이다. */
    String startNotice();

    /** 이번 살펴보기를 새 session 으로 시작할지다. */
    boolean renewSession();

    /** 살펴보기 turn 의 실행 줄이 생긴 직후에 불린다. */
    void started(Long executionId, String hermesRootSessionId);

    /** 이 turn 의 {@code tool.started} 마다 스트림을 읽는 스레드에서 불린다. 막히지 않게 곧바로 돌아온다. */
    void toolStarted(Long executionId);

    /** 성공한 답을 대화에 남길 글로 바꾼다. */
    CheckAnswer answer(Long executionId, String output);

    /** 멈췄을 때 남기는 알림 줄의 글이다. */
    String stoppedNotice();

    /**
     * 멈춘 turn 뒤에 그 대화의 대기 줄을 멈춰 둘지다. 사용자가 멈췄으면 참이고, Control Plane 이 상한으로 멈췄으면 거짓이다. 거짓이면 대기
     * 메시지가 그대로 다음 turn 으로 간다.
     */
    boolean holdPendingOnStop();
}
