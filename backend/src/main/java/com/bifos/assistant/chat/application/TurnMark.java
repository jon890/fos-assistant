package com.bifos.assistant.chat.application;

/**
 * 한 대화에 이 프로세스가 도는 turn 표시를 갖고 있는지다.
 *
 * <p>실행 줄의 상태가 아니라 메모리 표시로 판정한다. 흐름으로 도는 turn 은 Chief 가 끝나 뿌리 줄이
 * {@code SUCCEEDED} 가 된 뒤에도 자식이 돌기 때문이다.
 *
 * @param running 표시가 있다
 * @param executionId 표시에 붙은 실행 번호. 표시가 없거나 아직 번호가 붙기 전이면 null
 */
public record TurnMark(boolean running, Long executionId) {

    static final TurnMark NONE = new TurnMark(false, null);
}
