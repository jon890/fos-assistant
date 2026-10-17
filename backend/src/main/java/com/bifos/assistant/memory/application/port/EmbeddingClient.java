package com.bifos.assistant.memory.application.port;

import java.time.Duration;
import java.util.List;

/** 글을 벡터로 바꾸는 임베딩 서비스다. 부르는 쪽이 먼저 {@link MemoryEmbeddingProperties#enabled()} 를 본다. */
public interface EmbeddingClient {

    /**
     * 글마다 벡터 하나를 넣은 순서대로 낸다.
     *
     * @throws EmbeddingUnavailableException 서비스가 실패했거나 {@code timeout} 안에 답하지 않았거나 답의 모양이 틀렸다
     */
    List<float[]> embed(List<String> inputs, Duration timeout);
}
