package com.bifos.assistant.chat.domain;

public enum MessageRole {
    USER,
    ASSISTANT,
    /** Control Plane 이 대화에 적은 알림이다. 사람이 쓴 것도 에이전트가 답한 것도 아니다. */
    SYSTEM
}
