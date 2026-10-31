package com.bifos.assistant.chat.purge.application.port;

/** 지운 대화에서 나온 먼저 살펴보기 제안의 줄과 본문을 지우는 경계다. */
public interface ConversationSuggestionPurge {
    /**
     * 그 대화의 제안을 지운다. 받아들이지 않았거나 거절한 제안 줄은 본문과 함께 지우고, 받아들여 할 일이 된 줄은 남기되 제안 본문만 비운다.
     * 부르는 쪽의 대화 정리 트랜잭션 안에서 부른다.
     *
     * @return 지우거나 비운 줄 수
     */
    int eraseSuggestions(Long conversationId);
}
