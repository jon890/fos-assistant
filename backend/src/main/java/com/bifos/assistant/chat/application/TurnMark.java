package com.bifos.assistant.chat.application;

/**
 * 한 대화에 이 프로세스가 도는 turn 표시를 갖고 있는지다.
 *
 * <p>실행 줄의 상태와 별개로 turn 잠금의 메모리 표시로 판정한다.
 *
 * @param running 표시가 있다
 * @param executionId 표시에 붙은 실행 번호. 표시가 없거나 아직 번호가 붙기 전이면 null
 */
public record TurnMark(boolean running, Long executionId) {

    static final TurnMark NONE = new TurnMark(false, null);
}
