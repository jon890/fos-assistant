package com.bifos.assistant.context.application.dto;

/**
 * 실행에 넣을 공통 답변 지침의 범위다. 종류마다 드는 실행은 {@code docs/features/answer-quality.md} 의 「지침」 이 갖는다.
 *
 * <p>저장하지 않는다. 실행을 여는 쪽이 정해 {@code ContextAssembler} 에 넘긴다.
 */
public enum InstructionScope {
    /** 사용자 대화에서 시작된 실행. 기억 지침과 「최신 정보와 출처」 절을 넣고 turn 전용 지시에 날짜 줄을 붙인다. */
    CONVERSATION,
    /** 예약 작업이 연 실행과 그 트리. 기억 지침만 넣는다. */
    SCHEDULED,
    /** 먼저 살펴보기와 그 트리. 기억 지침도 넣지 않는다(ADR-080). */
    PROACTIVE;


    /** {@code memory_remember} 를 받는 실행이라 기억 지침을 넣는가. */
    public boolean remembers() {
        return this != PROACTIVE;
    }


    /** 「최신 정보와 출처」 절과 날짜 줄을 넣는가. */
    public boolean conversation() {
        return this == CONVERSATION;
    }
}
