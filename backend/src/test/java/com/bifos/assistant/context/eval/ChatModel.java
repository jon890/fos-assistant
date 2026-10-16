package com.bifos.assistant.context.eval;

/** 답 판정 하네스가 부르는 chat 모델이다. 실제 구현은 {@link OpenAiCompatibleChatModel} 이고 테스트는 대역을 쓴다. */
interface ChatModel {

    /**
     * system 글과 user 글로 답 하나를 받는다.
     *
     * @return 모델이 낸 글
     */
    String complete(String model, String system, String user);
}
