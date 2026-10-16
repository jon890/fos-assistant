package com.bifos.assistant.memory.application.relevance;

import com.bifos.assistant.memory.application.embedding.EmbeddingClient;
import com.bifos.assistant.memory.application.embedding.EmbeddingText;
import com.bifos.assistant.memory.application.embedding.EmbeddingUnavailableException;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 질문 하나를 임베딩한다. 꺼져 있거나 {@code query-timeout} 안에 답이 오지 않으면 빈 값이고, 부르는 쪽은 키워드 점수만 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryEmbedder {

    private final MemoryEmbeddingProperties properties;
    private final EmbeddingClient client;


    /** 임베딩을 쓰지 않는 계산이다. 테스트와 측정이 쓴다. */
    public static QueryEmbedder disabled() {
        return new QueryEmbedder(MemoryEmbeddingProperties.disabled(), null);
    }


    public Optional<float[]> embed(String question) {
        if (!properties.enabled() || question == null || question.isBlank()) {
            return Optional.empty();
        }
        try {
            // 위임 작업 글은 길 수 있다. 항목을 임베딩할 때와 같은 상한에서 자른다
            String text = EmbeddingText.of(question.strip(), null, properties.maxTextChars());
            return Optional.of(client.embed(List.of(text), properties.queryTimeout()).getFirst());
        } catch (EmbeddingUnavailableException ex) {
            log.warn("memory query embedding fell back to keywords exceptionClass={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
