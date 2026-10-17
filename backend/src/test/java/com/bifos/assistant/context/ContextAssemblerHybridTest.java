package com.bifos.assistant.context;

import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.context.application.MemorySearchSection;
import com.bifos.assistant.context.application.dto.AssembledContext;
import com.bifos.assistant.context.application.dto.ContextItem;
import com.bifos.assistant.context.application.dto.ContextSelection;
import com.bifos.assistant.context.application.dto.ContextSource;
import com.bifos.assistant.context.config.ContextProperties;
import com.bifos.assistant.context.config.MemorySearchSectionProperties;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.port.EmbeddingClient;
import com.bifos.assistant.memory.application.port.EmbeddingUnavailableException;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.MemoryEmbeddingRefresher;
import com.bifos.assistant.memory.application.MemoryLinkDiscovery;
import com.bifos.assistant.memory.application.KeywordScorer;
import com.bifos.assistant.memory.application.MemoryRelevance;
import com.bifos.assistant.memory.config.MemoryRelevanceProperties;
import com.bifos.assistant.memory.application.MemoryVectorCache;
import com.bifos.assistant.memory.application.QueryEmbedder;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 가짜 임베딩 서비스로 의미 점수가 조립에 쓰이고, 서비스가 실패하거나 꺼지면 키워드로 물러나는지 확인한다. */
@BackendIntegrationTest
class ContextAssemblerHybridTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "user1@example.com", "user1", 10L, UserRole.ADMIN);
    private static final String AGENT_CODE = "context-hybrid-test";
    private static final MemoryEmbeddingProperties ENABLED = new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, null, null, null, null);
    private static final String QUESTION = "바퀴 언제 바꿨지";

    @Autowired
    MemoryLinkDiscovery linkDiscovery;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryEmbeddingRepository embeddings;

    @Autowired
    AgentRepository agents;

    @Autowired
    ContextProperties properties;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestClock clock;

    @Autowired
    BackgroundTasks backgroundTasks;

    private final TopicClient client = new TopicClient();
    private Long agentId;


    @BeforeEach
    void setUp() {
        embeddings.deleteAll();
        repository.deleteAll();
        agentId = agents.findByCode(AGENT_CODE)
            .orElseGet(() -> agents.save(Agent.of(
                AGENT_CODE,
                "하이브리드 조립 검사",
                AGENT_CODE,
                "http://runtime.test/p/" + AGENT_CODE,
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                null)))
            .id();
    }


    @AfterEach
    void tearDown() {
        embeddings.deleteAll();
    }


    @Test
    @DisplayName("낱말이 겹치지 않아도 벡터가 가까운 항목을 질문과 관련된 기억 구역에 싣고 세 점수를 남긴다")
    void placesSemanticMatchWithHybridSelection() {
        Memory tires = group("자동차 관리", "타이어는 작년 11월에 네 짝을 모두 새로 끼웠다");
        group("장보기", "우유와 계란을 산다");
        refresh();

        AssembledContext result = assembler(ENABLED).assemble(ADMIN, agentId, QUESTION);

        ContextItem placed = searchItem(result);
        assertThat(placed.ref()).isEqualTo("memory:" + tires.id());
        assertThat(placed.selection().selector()).isEqualTo(ContextSelection.SEARCH_HYBRID);
        assertThat(placed.selection().semanticScore()).isGreaterThan(0.9);
        assertThat(placed.selection().score()).isPositive();
        assertThat(placed.selection().keywordScore()).isNull();
    }


    @Test
    @DisplayName("키워드와 의미가 함께 맞는 항목은 세 점수를 모두 남긴다")
    void keepsAllThreeScoresWhenBothMatch() {
        Memory tires = group("자동차 관리", "바퀴는 작년 11월에 네 짝을 모두 바꿨다");
        refresh();

        ContextItem placed = searchItem(assembler(ENABLED).assemble(ADMIN, agentId, QUESTION));

        assertThat(placed.ref()).isEqualTo("memory:" + tires.id());
        assertThat(placed.selection().keywordScore()).isNotNull();
        assertThat(placed.selection().semanticScore()).isNotNull();
        assertThat(placed.selection().score()).isNotNull();
    }


    @Test
    @DisplayName("임베딩 서비스가 실패하면 조립은 실패하지 않고 키워드로 고른 항목을 싣는다")
    void fallsBackToKeywordsWhenEmbeddingFails() {
        Memory peanut = group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");
        refresh();
        client.failing = true;

        ContextItem placed = searchItem(assembler(ENABLED).assemble(ADMIN, agentId, "땅콩 들어간 거 먹어도 돼?"));

        assertThat(placed.ref()).isEqualTo("memory:" + peanut.id());
        assertThat(placed.selection().selector()).isEqualTo(ContextSelection.SEARCH_KEYWORD);
        assertThat(placed.selection().semanticScore()).isNull();
    }


    @Test
    @DisplayName("임베딩이 꺼져 있으면 임베딩 서비스를 부르지 않는다")
    void doesNotCallClientWhenDisabled() {
        group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");

        AssembledContext result = assembler(MemoryEmbeddingProperties.disabled()).assemble(ADMIN, agentId, "땅콩 들어간 거 먹어도 돼?");

        assertThat(searchItem(result).selection().selector()).isEqualTo(ContextSelection.SEARCH_KEYWORD);
        assertThat(client.calls).isZero();
    }


    private ContextAssembler assembler(MemoryEmbeddingProperties embedding) {
        MemoryRelevance relevance = new MemoryRelevance(
            new KeywordScorer(), MemoryRelevanceProperties.defaults(), new MemoryVectorCache(embeddings, embedding));
        MemorySearchSection section = new MemorySearchSection(
            relevance, MemorySearchSectionProperties.defaults(), new QueryEmbedder(embedding, client));
        return new ContextAssembler(memories, properties, clock, section);
    }


    private void refresh() {
        new MemoryEmbeddingRefresher(
            ENABLED, client, embeddings, transactionManager, new MemoryVectorCache(embeddings, ENABLED), backgroundTasks, linkDiscovery)
                .refreshOnce();
    }


    private Memory group(String title, String content) {
        return memories.create(ADMIN, MemoryScope.GROUP, title, content, false);
    }


    private static ContextItem searchItem(AssembledContext result) {
        return result.bundle().items().stream()
            .filter(item -> item.source() == ContextSource.MEMORY_SEARCH)
            .findFirst()
            .orElseThrow();
    }


    /** 자동차와 바퀴 이야기에는 같은 쪽의 벡터를, 나머지에는 다른 쪽의 벡터를 내는 가짜 임베딩 서비스다. */
    private static final class TopicClient implements EmbeddingClient {

        private boolean failing;
        private int calls;


        @Override
        public List<float[]> embed(List<String> inputs, Duration timeout) {
            calls++;
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("임베딩 호출 동안 트랜잭션을 열어 두지 않는다")
                .isFalse();
            if (failing) {
                throw new EmbeddingUnavailableException("fake failure");
            }
            return inputs.stream()
                .map(text -> text.contains("자동차") || text.contains("바퀴") || text.contains("타이어")
                    ? new float[] {1f, 0.05f}
                    : new float[] {0f, 1f})
                .toList();
        }
    }
}
