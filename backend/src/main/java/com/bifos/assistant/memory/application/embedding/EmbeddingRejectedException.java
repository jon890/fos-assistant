package com.bifos.assistant.memory.application.embedding;

/** 임베딩 서비스가 요청을 4xx 로 거절했다. 같은 글을 다시 보내도 같은 답이 올 수 있다. 메시지에 기억 글을 넣지 않는다. */
public class EmbeddingRejectedException extends EmbeddingUnavailableException {

    public EmbeddingRejectedException(String message) {
        super(message);
    }
}
