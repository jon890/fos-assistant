package com.bifos.assistant.task.domain.type;

/** 발화한 실행의 결과를 어느 대화에 남기는지다(ADR-078). DB 에 이름 그대로 저장된다. */
public enum ConversationMode {
    /** 발화마다 새 대화를 연다. */
    NEW_PER_RUN,
    /** 한 대화에 이어 쌓는다. */
    SINGLE
}
