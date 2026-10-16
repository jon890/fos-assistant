package com.bifos.assistant.memory.relevance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.bifos.assistant.context.ContextSelection;
import com.bifos.assistant.memory.application.embedding.EmbeddingText;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.relevance.KeywordScorer;
import com.bifos.assistant.memory.application.relevance.MemoryRelevance;
import com.bifos.assistant.memory.application.relevance.MemoryRelevanceProperties;
import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.application.relevance.QueryEmbedder;
import com.bifos.assistant.memory.application.relevance.RelevanceCandidate;
import com.bifos.assistant.memory.application.relevance.RelevanceResult;
import com.bifos.assistant.memory.application.relevance.RelevanceScore;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 가짜 벡터로 키워드 순위와 의미 순위를 RRF 로 합치는 계산을 확인한다. 실제 모델은 부르지 않는다. */
class MemoryRelevanceTest {

    private static final MemoryEmbeddingProperties EMBEDDING = new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, null, null, null, null);
    private static final float[] QUERY = {1f, 0f, 0f};

    private final List<MemoryEmbedding> stored = new ArrayList<>();
    private final MemoryRelevance relevance = new MemoryRelevance(
        new KeywordScorer(), MemoryRelevanceProperties.defaults(), new MemoryVectorCache(repository(), EMBEDDING));


    @Test
    @DisplayName("키워드가 겹치지 않아도 코사인이 높은 항목은 하이브리드에서 점수를 받고 키워드만 쓰면 받지 못한다")
    void scoresSemanticOnlyMatchInHybrid() {
        RelevanceCandidate tires = candidate(1L, "자동차 관리", "타이어는 작년 11월에 네 짝을 모두 새로 끼웠다");
        store(tires, new float[] {0.9f, 0.1f, 0f});

        RelevanceResult hybrid = relevance.score("바퀴 언제 바꿨지", Optional.of(QUERY), List.of(tires));
        RelevanceResult keyword = relevance.score("바퀴 언제 바꿨지", Optional.empty(), List.of(tires));

        assertThat(hybrid.selector()).isEqualTo(MemoryRelevance.SEARCH_HYBRID);
        assertThat(hybrid.ranked()).singleElement().satisfies(score -> {
            assertThat(score.keyword()).isNull();
            assertThat(score.semantic()).isGreaterThan(0.9);
            assertThat(score.fused()).isCloseTo(1.0 / 61, within(1e-9));
        });
        assertThat(keyword.selector()).isEqualTo(MemoryRelevance.SEARCH_KEYWORD);
        assertThat(keyword.ranked()).isEmpty();
    }


    @Test
    @DisplayName("두 목록 모두에 있는 항목이 한쪽에만 있는 항목보다 위이고 값은 두 RRF 항의 합이다")
    void ranksItemsInBothListsFirst() {
        RelevanceCandidate both = candidate(1L, "땅콩 알레르기", "둘째는 땅콩을 먹으면 안 된다");
        RelevanceCandidate keywordOnly = candidate(2L, "알레르기 약국", "약 이름 목록");
        RelevanceCandidate semanticOnly = candidate(3L, "아이 음식 주의", "견과류는 피한다");
        store(both, new float[] {0.8f, 0.2f, 0f});
        store(keywordOnly, new float[] {0f, 1f, 0f});
        store(semanticOnly, new float[] {0.95f, 0.05f, 0f});

        List<RelevanceScore> ranked = relevance.score("땅콩 알레르기", Optional.of(QUERY), List.of(both, keywordOnly, semanticOnly)).ranked();

        assertThat(ranked).extracting(RelevanceScore::memoryId).startsWith(1L).containsExactlyInAnyOrder(1L, 2L, 3L);
        // 키워드 1위, 의미 2위다
        assertThat(ranked.getFirst().fused()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-9));
        assertThat(ranked.getFirst().keyword()).isNotNull();
        assertThat(ranked.getFirst().semantic()).isNotNull();
    }


    @Test
    @DisplayName("코사인이 기준 아래이고 키워드도 없는 항목과 본문을 고친 항목의 낡은 벡터는 점수가 없다")
    void ignoresLowSimilarityAndStaleVectors() {
        RelevanceCandidate far = candidate(1L, "장보기", "우유와 계란");
        RelevanceCandidate edited = candidate(2L, "자동차 관리", "타이어는 올해 3월에 갈았다");
        store(far, new float[] {0f, 1f, 0f});
        store(candidate(2L, "자동차 관리", "타이어는 작년 11월에 갈았다"), new float[] {1f, 0f, 0f});

        assertThat(relevance.score("바퀴 언제 바꿨지", Optional.of(QUERY), List.of(far, edited)).ranked()).isEmpty();
    }


    @Test
    @DisplayName("캐시에 옛 지문의 벡터가 들어가 있어도 저장소의 줄이 바뀌면 다음 계산에서 다시 읽어 쓴다")
    void reloadsVectorWhenCachedFingerprintIsStale() {
        RelevanceCandidate tires = candidate(1L, "자동차 관리", "타이어는 올해 3월에 갈았다");
        store(candidate(1L, "자동차 관리", "타이어는 작년 11월에 갈았다"), new float[] {1f, 0f, 0f});
        assertThat(relevance.score("바퀴 언제 바꿨지", Optional.of(QUERY), List.of(tires)).ranked()).isEmpty();

        stored.clear();
        store(tires, new float[] {1f, 0f, 0f});

        assertThat(relevance.score("바퀴 언제 바꿨지", Optional.of(QUERY), List.of(tires)).ranked())
            .extracting(RelevanceScore::memoryId)
            .containsExactly(1L);
    }


    @Test
    @DisplayName("질문 임베딩은 항목과 같은 글자 수 상한에서 잘라 보낸다")
    void truncatesLongQuestionBeforeEmbedding() {
        List<String> sent = new ArrayList<>();
        MemoryEmbeddingProperties small = new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, null, null, 10, null);
        QueryEmbedder embedder = new QueryEmbedder(small, (inputs, timeout) -> {
            sent.addAll(inputs);
            return List.of(new float[] {1f});
        });

        assertThat(embedder.embed("가".repeat(30))).isPresent();
        assertThat(sent).containsExactly("가".repeat(10));
    }


    @Test
    @DisplayName("규칙 이름은 조립의 선택 근거와 같은 값이다")
    void sharesSelectorsWithContextSelection() {
        assertThat(MemoryRelevance.SEARCH_HYBRID).isEqualTo(ContextSelection.SEARCH_HYBRID);
        assertThat(MemoryRelevance.SEARCH_KEYWORD).isEqualTo(ContextSelection.SEARCH_KEYWORD);
    }


    private static RelevanceCandidate candidate(Long id, String title, String body) {
        return new RelevanceCandidate(id, title, body);
    }


    private void store(RelevanceCandidate candidate, float[] vector) {
        String text = EmbeddingText.of(candidate.title(), candidate.plainBody(), EMBEDDING.maxTextChars());
        stored.add(MemoryEmbedding.of(
            candidate.memoryId(), EMBEDDING.model(), EmbeddingText.fingerprint(EMBEDDING.model(), text), vector, Instant.EPOCH));
    }


    @SuppressWarnings("unchecked")
    private MemoryEmbeddingRepository repository() {
        MemoryEmbeddingRepository repository = Mockito.mock(MemoryEmbeddingRepository.class);
        Mockito.when(repository.findByMemoryIdIn(Mockito.any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0, Collection.class);
            return stored.stream().filter(row -> ids.contains(row.memoryId())).toList();
        });
        return repository;
    }
}
