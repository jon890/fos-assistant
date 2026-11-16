package com.bifos.assistant.chat.conversation.domain.type;

/** steer 를 받은 순간 그 turn 에서 돈 자식의 종류다. 화면이 종류에 맞는 안내 줄을 그린다(ADR-20261116 / run-steer). */
public enum SteerChild {
    /** Hermes 안의 하위 에이전트. 자식이 끝난 뒤 부모에 들어간다. */
    SUBAGENT,
    /** {@code agent_delegate} 로 맡긴 실행. 부모가 기다리지 않아 글은 부모에 곧바로 들어가고 자식은 바뀌지 않는다. */
    DELEGATION
}
