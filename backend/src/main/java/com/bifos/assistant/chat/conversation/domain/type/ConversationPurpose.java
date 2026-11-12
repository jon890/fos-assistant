package com.bifos.assistant.chat.conversation.domain.type;

/** 대화가 무엇을 하려고 열렸는지다. 만들 때 정하고 바뀌지 않는다. */
public enum ConversationPurpose {
    /** 사용자가 묻고 에이전트가 답하는 보통 대화. */
    CHAT,
    /** 먼저 살펴보기의 결과가 남는 점검 대화(ADR-080). 사용자와 에이전트마다 하나를 이어 쓴다. */
    CHECK,
    /**
     * 기본 비서와 늘 이어지는 대화방(docs/features/agent-team/spec.md 의 REQ8). 사용자마다 하나를 이어 쓰고, 목록에서 빠져 사이드바
     * 맨 위에 따로 보인다.
     */
    ROOM
}
