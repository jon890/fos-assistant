package com.bifos.assistant.memory.application.embedding;

/** 임베딩 서비스가 실패했거나 늦었다. 메시지에 기억 글을 넣지 않는다. */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }


    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
