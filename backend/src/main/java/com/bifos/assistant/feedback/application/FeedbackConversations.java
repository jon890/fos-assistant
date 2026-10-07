package com.bifos.assistant.feedback.application;

/** 사건을 남길 대화가 아직 있는지 묻는 조회다. 대화를 가진 쪽이 구현한다. */
public interface FeedbackConversations {

    /** 지우지 않은 대화인가. 없는 대화와 지운 대화는 거짓이다. 대화 삭제와 차례를 맞추도록 부르는 트랜잭션이 끝날 때까지 그 줄을 잠근다. */
    boolean isActive(Long conversationId);
}
