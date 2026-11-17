package com.bifos.assistant.chat.purge.application.port;

/** 지운 대화의 Hermes 내장 위임 결과 글을 비우는 경계다(ADR-20261117 / native-delegation-wake). 등록 줄은 실행 기록과 함께 남긴다. */
public interface ConversationNativeResultPurge {

    /** 그 대화 실행을 origin 으로 둔 등록 줄의 맡긴 일과 결과 글과 key 를 비운다. 대화 줄을 잠근 부르는 쪽의 트랜잭션 안에서만 돈다. */
    void eraseBodies(Long conversationId);
}
