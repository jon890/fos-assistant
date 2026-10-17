package com.bifos.assistant.context.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.MemorySearchSection;
import com.bifos.assistant.context.MemorySearchSectionProperties;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Case;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.embedding.EmbeddingClient;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingRefresher;
import com.bifos.assistant.memory.application.MemoryLinkDiscovery;
import com.bifos.assistant.memory.application.embedding.OpenAiCompatibleEmbeddingClient;
import com.bifos.assistant.memory.application.relevance.KeywordScorer;
import com.bifos.assistant.memory.application.relevance.MemoryRelevance;
import com.bifos.assistant.memory.application.relevance.MemoryRelevanceProperties;
import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.application.relevance.QueryEmbedder;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TestContextAssemblers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 실제 임베딩 서비스로 사례 묶음을 {@code hybrid} 모드로 조립해 키워드만 쓴 {@code factsOn} 과 지표를 견준다. 방법은 {@code docs/features/memory.md} 의
 * 「합성 측정」 이 갖는다.
 *
 * <p>{@code MEMORY_EMBEDDING_BASE_URL} 이 없으면 건너뛴다. {@code MEMORY_EMBEDDING_MODEL} 이 없으면 운영 기본 모델 이름을 쓴다. 게이트는 걸지 않고 보고서만 남긴다. 모델 후보를 견주고
 * {@code semantic-min-similarity} 기본값을 정할 때 쓴다.
 */
@Tag("embedding-eval")
@BackendIntegrationTest
class MemoryEmbeddingEvalTest {

    private static final MemoryEvalDataset DATASET = MemoryEvalDataset.load();
    private static final Path REPORT_DIR = Path.of("build", "reports", "memory-eval");
    private static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");
    private static final String FACTS_ON = "factsOn";
    private static final String HYBRID = "hybrid";
    /** {@code application.yml} 의 {@code assistant.memory.embedding.model} 기본값과 같다. */
    private static final String DEFAULT_MODEL = "onnx-community/bge-m3-ONNX";

    @Autowired
    MemoryLinkDiscovery linkDiscovery;

    @Autowired
    MemoryService memoryService;

    @Autowired
    ContextProperties properties;

    @Autowired
    MemoryRepository memories;

    @Autowired
    MemoryEmbeddingRepository embeddings;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestClock clock;

    @Autowired
    BackgroundTasks backgroundTasks;

    private MemoryEvalFixture fixture;


    @AfterEach
    void tearDown() {
        embeddings.deleteAll();
        if (fixture != null) {
            fixture.tearDown();
        }
    }


    @Test
    @DisplayName("실제 임베딩 서비스로 사례 묶음을 하이브리드로 조립해 키워드만 쓴 조립과 지표를 견주고 질문 임베딩 지연을 남긴다")
    void comparesHybridWithKeywordAssembly() {
        String baseUrl = System.getenv("MEMORY_EMBEDDING_BASE_URL");
        String model = System.getenv().getOrDefault("MEMORY_EMBEDDING_MODEL", DEFAULT_MODEL);
        Assumptions.assumeTrue(
            baseUrl != null && !baseUrl.isBlank() && !model.isBlank(),
            "MEMORY_EMBEDDING_BASE_URL 이 없어 임베딩 측정을 건너뛴다");
        MemoryEmbeddingProperties settings = new MemoryEmbeddingProperties(baseUrl, model, Duration.ofSeconds(5), null, null, 1_000, null, null);
        List<Double> queryMillis = new ArrayList<>();
        EmbeddingClient real = new OpenAiCompatibleEmbeddingClient(settings);
        EmbeddingClient timed = (inputs, timeout) -> {
            long started = System.nanoTime();
            List<float[]> vectors = real.embed(inputs, timeout);
            if (inputs.size() == 1) {
                queryMillis.add((System.nanoTime() - started) / 1_000_000.0);
            }
            return vectors;
        };
        MemoryVectorCache cache = new MemoryVectorCache(embeddings, settings);
        MemoryEmbeddingRefresher refresher = new MemoryEmbeddingRefresher(
            settings, timed, embeddings, transactionManager, cache, backgroundTasks, linkDiscovery);
        MemorySearchSection hybridSection = new MemorySearchSection(
            new MemoryRelevance(new KeywordScorer(), MemoryRelevanceProperties.defaults(), cache),
            MemorySearchSectionProperties.defaults(),
            new QueryEmbedder(settings, timed));
        ContextAssembler hybrid = new ContextAssembler(memoryService, properties, clock, hybridSection);
        ContextAssembler keyword = TestContextAssemblers.of(memoryService, properties, clock);

        clock.set(BASE);
        fixture = new MemoryEvalFixture(DATASET, memories, agents, grants, jdbc, clock);
        Map<String, Long> agentIds = fixture.createAgents();
        MemoryEvalScoreboard scoreboard = new MemoryEvalScoreboard(DATASET.version());
        for (Case each : DATASET.cases()) {
            Map<String, Long> ids = fixture.seed(each);
            AssembledContext keywordContext = keyword.assemble(fixture.userOf(each.asker()), agentIds.get(each.agent()), each.question());
            scoreboard.add(FACTS_ON, MemoryRecallEvalTest.verdictOf(each, ids, keywordContext));
            refresher.refreshOnce();
            AssembledContext hybridContext = hybrid.assemble(fixture.userOf(each.asker()), agentIds.get(each.agent()), each.question());
            scoreboard.add(HYBRID, MemoryRecallEvalTest.verdictOf(each, ids, hybridContext));
            embeddings.deleteAll();
        }

        queryMillis.sort(Double::compare);
        String latency = queryMillis.isEmpty()
            ? "질문 임베딩 지연: 잰 값 없음\n"
            : String.format(
                Locale.ROOT,
                "질문 임베딩 지연(%d건): p50 %.1fms, p95 %.1fms\n",
                queryMillis.size(),
                queryMillis.get(queryMillis.size() / 2),
                queryMillis.get(Math.min(queryMillis.size() - 1, (int)Math.ceil(queryMillis.size() * 0.95) - 1)));
        String markdown = "모델 " + model + "\n\n" + latency + "\n" + scoreboard.markdown();
        write(model, markdown);
        assertThat(scoreboard.boundaryViolations()).as("권한 경계를 넘어 문맥 묶음에 든 항목").isEmpty();
    }


    private static void write(String model, String markdown) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve("embedding-" + model.replaceAll("[^A-Za-z0-9._-]", "_") + ".md"), markdown);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.println(markdown);
    }
}
