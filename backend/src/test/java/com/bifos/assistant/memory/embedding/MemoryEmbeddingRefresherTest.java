package com.bifos.assistant.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.embedding.EmbeddingClient;
import com.bifos.assistant.memory.application.embedding.EmbeddingUnavailableException;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingRefresher;
import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 임베딩 대상만 벡터를 채우고 대상에서 빠진 줄을 지우는지 가짜 임베딩 서비스로 확인한다. */
@BackendIntegrationTest
class MemoryEmbeddingRefresherTest {

    private static final CurrentUser USER = new CurrentUser(1L, "user1@example.com", "user1", 10L, UserRole.ADMIN);
    private static final MemoryEmbeddingProperties ENABLED =
        new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, 2, 16, null, null);

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryEmbeddingRepository embeddings;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private final FakeClient client = new FakeClient();


    @BeforeEach
    void setUp() {
        embeddings.deleteAll();
        repository.deleteAll();
    }


    @AfterEach
    void tearDown() {
        embeddings.deleteAll();
    }


    @Test
    @DisplayName("일반 항목만 줄이 생기고 민감 암호화 제안 보관 출처 원문 항목은 생기지 않는다")
    void embedsOnlyPlainSearchableItems() {
        Memory plain = memories.create(USER, MemoryScope.USER, "타이어", "작년 11월에 갈았다", false);
        Memory group = memories.create(USER, MemoryScope.GROUP, "보일러", "10월에 점검한다", false);
        memories.create(USER, MemoryScope.USER, "민감", "혈액형", Memory.DEFAULT_COLLECTION, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        memories.create(USER, MemoryScope.USER, "보관", "오래된 메모", Memory.DEFAULT_COLLECTION, MemoryRetrieval.ARCHIVE, MemorySensitivity.NORMAL);
        memories.create(USER, MemoryScope.USER, "항상", "항상 싣는 메모", true);
        memories.proposeUser(USER, "제안", "아직 받아들이지 않은 메모", null);
        Memory source = memories.create(USER, MemoryScope.USER, "출처", "원문", false);
        jdbc.update("UPDATE memory SET entry_type = 'SOURCE' WHERE id = ?", source.id());
        Memory sealed = memories.create(USER, MemoryScope.USER, "봉인", "본문", false);
        jdbc.update("UPDATE memory SET content_key_id = 'test-1' WHERE id = ?", sealed.id());

        int written = refresher(ENABLED).refreshOnce();

        assertThat(written).isEqualTo(2);
        assertThat(embeddings.findAll()).extracting(MemoryEmbedding::memoryId).containsExactlyInAnyOrder(plain.id(), group.id());
        assertThat(client.inputs()).containsExactly("타이어\n작년 11월에 갈았다", "보일러\n10월에 점검한다");
        MemoryEmbedding row = embeddings.findById(plain.id()).orElseThrow();
        assertThat(row.model()).isEqualTo("test-model");
        assertThat(row.dimensions()).isEqualTo(3);
        assertThat(row.vector()).containsExactly(client.vectorOf("타이어\n작년 11월에 갈았다"));
        assertThat(refresher(ENABLED).refreshOnce()).as("바뀐 것이 없는 두 번째 주기").isZero();
    }


    @Test
    @DisplayName("본문을 고치면 다음 주기에 지문이 바뀌고 민감으로 바꾸거나 지우면 줄이 지워진다")
    void refreshesChangedAndRemovesExcludedRows() {
        Memory edited = memories.create(USER, MemoryScope.USER, "타이어", "작년 11월에 갈았다", false);
        Memory hidden = memories.create(USER, MemoryScope.USER, "혈액형", "O형이다", false);
        Memory gone = memories.create(USER, MemoryScope.USER, "지울 것", "곧 지운다", false);
        refresher(ENABLED).refreshOnce();
        byte[] before = embeddings.findById(edited.id()).orElseThrow().textFingerprint();

        memories.update(USER, edited.id(), "올해 3월에 다시 갈았다", false);
        memories.update(USER, hidden.id(), "O형이다", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        jdbc.update("DELETE FROM memory WHERE id = ?", gone.id());
        int written = refresher(ENABLED).refreshOnce();

        assertThat(written).isEqualTo(1);
        assertThat(embeddings.findAll()).extracting(MemoryEmbedding::memoryId).containsExactly(edited.id());
        assertThat(embeddings.findById(edited.id()).orElseThrow().textFingerprint()).isNotEqualTo(before);
    }


    @Test
    @DisplayName("묶음 하나가 실패하면 그 주기의 나머지 묶음을 부르지 않고 다음 주기에 이어 채운다")
    void stopsRunOnFailureAndResumesNextRun() {
        for (int i = 0; i < 5; i++) {
            memories.create(USER, MemoryScope.USER, "항목 " + i, "본문 " + i, false);
        }
        client.failOnCall(2);

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(2);
        assertThat(client.calls()).isEqualTo(2);
        assertThat(embeddings.count()).isEqualTo(2);

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(3);
        assertThat(embeddings.count()).isEqualTo(5);
    }


    @Test
    @DisplayName("주소나 모델이 비면 임베딩 서비스를 한 번도 부르지 않는다")
    void doesNotCallWhenDisabled() {
        memories.create(USER, MemoryScope.USER, "타이어", "작년 11월에 갈았다", false);

        assertThat(refresher(MemoryEmbeddingProperties.disabled()).refreshOnce()).isZero();
        refresher(new MemoryEmbeddingProperties("http://embedding.test", "", null, null, null, null, null, null)).refresh();

        assertThat(client.calls()).isZero();
        assertThat(embeddings.count()).isZero();
    }


    private MemoryEmbeddingRefresher refresher(MemoryEmbeddingProperties properties) {
        return new MemoryEmbeddingRefresher(
            properties, client, embeddings, transactionManager, new MemoryVectorCache(embeddings, properties), clock);
    }


    /** 글마다 정해진 세 차원 벡터를 내는 가짜 서비스다. 정한 차례의 호출은 실패한다. */
    private static final class FakeClient implements EmbeddingClient {

        private final List<String> inputs = new ArrayList<>();
        private int calls;
        private int failOn = -1;


        @Override
        public List<float[]> embed(List<String> batch, Duration timeout) {
            calls++;
            if (calls == failOn) {
                throw new EmbeddingUnavailableException("fake failure");
            }
            inputs.addAll(batch);
            return batch.stream().map(this::vectorOf).toList();
        }


        float[] vectorOf(String text) {
            return new float[] {text.length(), text.hashCode() % 7, 1f};
        }


        void failOnCall(int call) {
            failOn = call;
        }


        int calls() {
            return calls;
        }


        List<String> inputs() {
            return List.copyOf(inputs);
        }
    }
}
