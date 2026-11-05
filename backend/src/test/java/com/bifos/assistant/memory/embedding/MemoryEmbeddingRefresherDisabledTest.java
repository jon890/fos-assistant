package com.bifos.assistant.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.memory.application.MemoryEmbeddingRefresher;
import com.bifos.assistant.memory.application.MemoryTexts;
import com.bifos.assistant.memory.application.MemoryVectorCache;
import com.bifos.assistant.memory.application.port.EmbeddingClient;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;

/** 임베딩 설정이 꺼져 있으면 Spring 과 DB 없이도 아무것도 부르지 않는 것을 확인한다. */
class MemoryEmbeddingRefresherDisabledTest {

    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final MemoryEmbeddingRepository embeddings = mock(MemoryEmbeddingRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final BackgroundTasks backgroundTasks = mock(BackgroundTasks.class);


    static Stream<MemoryEmbeddingProperties> disabledProperties() {
        return Stream.of(
            MemoryEmbeddingProperties.disabled(),
            new MemoryEmbeddingProperties("http://embedding.test", "", null, null, null, null, null, null),
            new MemoryEmbeddingProperties("", "test-model", null, null, null, null, null, null));
    }


    @ParameterizedTest
    @MethodSource("disabledProperties")
    @DisplayName("주소나 모델이 비면 임베딩 서비스와 저장소와 백그라운드 작업을 한 번도 부르지 않는다")
    void doesNotCallWhenDisabled(MemoryEmbeddingProperties properties) {
        MemoryEmbeddingRefresher refresher = new MemoryEmbeddingRefresher(
            properties,
            client,
            embeddings,
            transactionManager,
            mock(MemoryVectorCache.class),
            backgroundTasks,
            mock(MemoryTexts.class));

        assertThat(refresher.refreshOnce()).isZero();
        refresher.refresh();

        verifyNoInteractions(client, embeddings, transactionManager, backgroundTasks);
    }
}
