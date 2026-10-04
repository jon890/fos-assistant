package com.bifos.assistant.chat.domain.type;

/** 대화가 무엇을 하려고 열렸는지다. 만들 때 정하고 바뀌지 않는다. */
public enum ConversationPurpose {
    /** 사용자가 묻고 에이전트가 답하는 보통 대화. */
    CHAT,
    /** 먼저 살펴보기의 결과가 남는 점검 대화(ADR-077). 사용자와 에이전트마다 하나를 이어 쓴다. */
    CHECK
}
