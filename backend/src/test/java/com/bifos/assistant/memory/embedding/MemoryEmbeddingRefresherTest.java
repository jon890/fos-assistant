package com.bifos.assistant.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.embedding.EmbeddingClient;
import com.bifos.assistant.memory.application.embedding.EmbeddingRejectedException;
import com.bifos.assistant.memory.application.embedding.EmbeddingUnavailableException;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.embedding.MemoryEmbeddingRefresher;
import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 임베딩 대상만 벡터를 채우고 대상에서 빠진 줄을 지우는지 가짜 임베딩 서비스로 확인한다. */
@BackendIntegrationTest
class MemoryEmbeddingRefresherTest {

    private static final CurrentUser USER = new CurrentUser(1L, "user1@example.com", "user1", 10L, UserRole.ADMIN);
    private static final MemoryEmbeddingProperties ENABLED = new MemoryEmbeddingProperties("http://embedding.test", "test-model", null, null, 2, 16, null, null);

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

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryLinkRepository links;

    @Autowired
    TrackingBackgroundTasks backgroundTasks;

    private final FakeClient client = new FakeClient();


    @BeforeEach
    void setUp() {
        embeddings.deleteAll();
        links.deleteAll();
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
    @DisplayName("대체된 항목은 줄이 생기지 않고 이미 있던 줄은 다음 주기에 지워지며 대체를 끊으면 다시 생긴다")
    void skipsAndRemovesSupersededItems() {
        Memory old = memories.create(USER, MemoryScope.USER, "옛 주소", "서울에 산다", false);
        Memory fresh = memories.create(USER, MemoryScope.USER, "새 주소", "부산에 산다", false);
        Memory other = memories.create(USER, MemoryScope.USER, "다른 옛 것", "대체될 메모", false);
        MemoryLink before = linkService.proposeSupersede(USER, fresh.id(), other.id(), MemoryLinkOrigin.AGENT, null);
        linkService.accept(USER, before.id());
        refresher(ENABLED).refreshOnce();
        assertThat(embeddings.findAll())
            .extracting(MemoryEmbedding::memoryId)
            .containsExactlyInAnyOrder(old.id(), fresh.id());

        MemoryLink link = linkService.proposeSupersede(USER, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
        linkService.accept(USER, link.id());
        refresher(ENABLED).refreshOnce();

        assertThat(embeddings.findAll()).extracting(MemoryEmbedding::memoryId).containsExactly(fresh.id());

        linkService.revoke(USER, link.id());
        refresher(ENABLED).refreshOnce();

        assertThat(embeddings.findAll())
            .extracting(MemoryEmbedding::memoryId)
            .containsExactlyInAnyOrder(old.id(), fresh.id());
    }


    @Test
    @DisplayName("서비스가 내려가 묶음과 한 건씩 다시 보낸 것이 모두 실패하면 그 주기를 끝내고 다음 주기에 이어 채운다")
    void stopsRunWhenServiceIsDownAndResumesNextRun() {
        for (int i = 0; i < 5; i++) {
            memories.create(USER, MemoryScope.USER, "항목 " + i, "본문 " + i, false);
        }
        client.failFromCall(2);

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(2);
        // 둘째 묶음 하나와 그 두 항목을 한 건씩 보낸 둘. 셋째 묶음은 부르지 않는다
        assertThat(client.calls()).isEqualTo(4);
        assertThat(embeddings.count()).isEqualTo(2);

        client.failFromCall(-1);
        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(3);
        assertThat(embeddings.count()).isEqualTo(5);
    }


    @Test
    @DisplayName("늘 늦거나 서버 오류를 내는 항목이 앞 묶음에 있어도 한 건씩 다시 보내 뒤 항목을 채운다")
    void skipsItemThatAlwaysFailsWithoutBlockingTheRest() {
        Memory slow = memories.create(USER, MemoryScope.USER, "늘 늦는 글", "서버가 끝내지 못하는 본문", false);
        Memory next = memories.create(USER, MemoryScope.USER, "다음 글", "보통 본문", false);
        Memory last = memories.create(USER, MemoryScope.USER, "마지막 글", "보통 본문", false);
        client.failOn("끝내지 못하는");

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(2);

        assertThat(embeddings.findAll()).extracting(MemoryEmbedding::memoryId).containsExactlyInAnyOrder(next.id(), last.id());
        assertThat(embeddings.findById(slow.id())).isEmpty();
    }


    @Test
    @DisplayName("주기 도중 민감으로 바뀐 항목은 다음 묶음을 보내기 직전에 다시 읽어 보내지 않는다")
    void rereadsBatchBeforeSendingAndSkipsItemsTurnedSensitive() {
        memories.create(USER, MemoryScope.USER, "첫째", "첫 본문", false);
        memories.create(USER, MemoryScope.USER, "둘째", "둘째 본문", false);
        Memory third = memories.create(USER, MemoryScope.USER, "셋째", "셋째 평문", false);
        client.onFirstCall(() -> memories.update(USER, third.id(), "셋째 평문", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE));

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(2);

        assertThat(client.inputs()).noneMatch(input -> input.contains("셋째"));
        assertThat(embeddings.findById(third.id())).isEmpty();
    }


    @Test
    @DisplayName("임베딩을 기다리는 동안 지운 항목의 벡터 줄은 넣지 않는다")
    void doesNotWriteVectorOfItemDeletedWhileEmbedding() {
        Memory kept = memories.create(USER, MemoryScope.USER, "남길 글", "보통 본문", false);
        Memory deleted = memories.create(USER, MemoryScope.USER, "지울 글", "곧 지운다", false);
        client.onFirstCall(() -> memories.delete(USER, deleted.id()));

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(1);

        assertThat(embeddings.findAll()).extracting(MemoryEmbedding::memoryId).containsExactly(kept.id());
    }


    @Test
    @DisplayName("서비스가 거절하는 항목은 한 건씩 다시 보내 그 항목만 건너뛰고 뒤 항목을 채운다")
    void skipsRejectedItemWithoutBlockingTheRest() {
        Memory poison = memories.create(USER, MemoryScope.USER, "거절될 글", "서비스가 받지 않는 본문", false);
        Memory next = memories.create(USER, MemoryScope.USER, "다음 글", "보통 본문", false);
        Memory last = memories.create(USER, MemoryScope.USER, "마지막 글", "보통 본문", false);
        client.reject("받지 않는");

        assertThat(refresher(ENABLED).refreshOnce()).isEqualTo(2);

        assertThat(embeddings.findAll()).extracting(MemoryEmbedding::memoryId).containsExactlyInAnyOrder(next.id(), last.id());
        assertThat(embeddings.findById(poison.id())).isEmpty();
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


    @Test
    @DisplayName("일정 실행은 스케줄러 스레드가 아니라 가상 스레드에서 돌고 앞 주기가 끝나기 전에는 다시 띄우지 않는다")
    void runsScheduledRefreshOffSchedulerThreadOnce() throws InterruptedException {
        memories.create(USER, MemoryScope.USER, "타이어", "작년 11월에 갈았다", false);
        CountDownLatch release = new CountDownLatch(1);
        client.blockUntil(release);
        MemoryEmbeddingRefresher refresher = refresher(ENABLED);

        refresher.refresh();
        refresher.refresh();
        release.countDown();
        backgroundTasks.awaitIdle(Duration.ofSeconds(10));

        assertThat(client.calls()).isEqualTo(1);
        assertThat(client.threadNames()).allMatch(name -> name.startsWith("memory-embedding-refresh"));
        assertThat(embeddings.count()).isEqualTo(1);
    }


    private MemoryEmbeddingRefresher refresher(MemoryEmbeddingProperties properties) {
        return new MemoryEmbeddingRefresher(
            properties, client, embeddings, transactionManager, new MemoryVectorCache(embeddings, properties), backgroundTasks, clock);
    }


    /** 글마다 정해진 세 차원 벡터를 내는 가짜 서비스다. 정한 차례의 호출은 실패한다. */
    private static final class FakeClient implements EmbeddingClient {

        private final List<String> inputs = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private int calls;
        private CountDownLatch blocker;
        private Runnable firstCall;
        private String rejected;
        private String unavailable;
        private int failFrom = -1;


        @Override
        public List<float[]> embed(List<String> batch, Duration timeout) {
            calls++;
            threadNames.add(Thread.currentThread().getName());
            if (calls == 1 && firstCall != null) {
                firstCall.run();
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("임베딩 호출 동안 트랜잭션을 열어 두지 않는다")
                .isFalse();
            if (blocker != null) {
                try {
                    blocker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (rejected != null && batch.stream().anyMatch(text -> text.contains(rejected))) {
                throw new EmbeddingRejectedException("fake rejection");
            }
            if (unavailable != null && batch.stream().anyMatch(text -> text.contains(unavailable))) {
                throw new EmbeddingUnavailableException("fake timeout");
            }
            if (failFrom > 0 && calls >= failFrom) {
                throw new EmbeddingUnavailableException("fake outage");
            }
            inputs.addAll(batch);
            return batch.stream().map(this::vectorOf).toList();
        }


        float[] vectorOf(String text) {
            return new float[] {text.length(), text.hashCode() % 7, 1f};
        }


        void failOn(String text) {
            unavailable = text;
        }


        void failFromCall(int call) {
            failFrom = call;
        }


        void reject(String text) {
            rejected = text;
        }


        void onFirstCall(Runnable action) {
            firstCall = action;
        }


        void blockUntil(CountDownLatch latch) {
            blocker = latch;
        }


        List<String> threadNames() {
            return List.copyOf(threadNames);
        }


        int calls() {
            return calls;
        }


        List<String> inputs() {
            return List.copyOf(inputs);
        }
    }
}
