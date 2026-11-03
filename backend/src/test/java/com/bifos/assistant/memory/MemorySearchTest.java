package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.EmbeddingText;
import com.bifos.assistant.memory.config.MemoryEmbeddingProperties;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.application.dto.MemorySearchCursor;
import com.bifos.assistant.memory.application.dto.MemorySearchItem;
import com.bifos.assistant.memory.application.dto.MemorySearchPage;
import com.bifos.assistant.memory.application.MemoryRelevance;
import com.bifos.assistant.memory.application.MemoryVectorCache;
import com.bifos.assistant.memory.application.MemoryTexts;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MemorySearchSqlProbe;
import com.bifos.assistant.testsupport.MysqlCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 실제 repository의 관련도 검색, 권한 경계, 커서와 SQL 투영을 확인한다. 원문은 출력하지 않는다. */
@BackendIntegrationTest
@MysqlCase(MysqlCase.Reason.COLLATION)
public class MemorySearchTest {
    static final CurrentUser USER = new CurrentUser(811L, "search@example.test", "검색 검사", 10L, UserRole.ADMIN);
    static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"));
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Autowired
    MemoryTexts texts;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MemoryEmbeddingProperties embeddingProperties;

    @Autowired
    MemoryVectorCache vectorCache;


    @BeforeEach
    void setUp() {
        MemorySearchSqlProbe.reset();
        MemoryEmbeddingRows.clearAll(jdbc);
        vectorCache.clear();
        repository.deleteAll();
    }


    @AfterEach
    void tearDown() {
        MemorySearchSqlProbe.reset();
    }


    @Test
    @DisplayName("권한 상태 종류와 꺼내는 방식 밖의 항목은 제목과 본문에 질문 낱말이 있어도 검색되지 않는다")
    void excludesEveryUnreadableBoundary() {
        Memory own = save(
            "일치 개인", MemoryScope.USER, USER.id(), null, "core", MemoryRetrieval.SEARCH);
        Memory group = save(
            "일치 공유",
            MemoryScope.GROUP,
            null,
            USER.groupId(),
            "core",
            MemoryRetrieval.SEARCH);
        List<Memory> hidden = List.of(
            save("다른 사용자 경계", MemoryScope.USER, 812L, null, "core", MemoryRetrieval.SEARCH),
            save("다른 그룹 경계", MemoryScope.GROUP, null, 20L, "core", MemoryRetrieval.SEARCH),
            save(
                "다른 collection 경계",
                MemoryScope.USER,
                USER.id(),
                null,
                "career",
                MemoryRetrieval.SEARCH),
            repository.save(Memory.proposedUser(USER.id(), "승인 전 경계", "합성", 1L, null, NOW)),
            save(
                "거절됨 경계",
                MemoryScope.USER,
                USER.id(),
                null,
                "core",
                MemoryRetrieval.SEARCH),
            save(
                "출처 원문 경계",
                MemoryScope.USER,
                USER.id(),
                null,
                "core",
                MemoryRetrieval.SEARCH),
            save(
                "보관됨 경계",
                MemoryScope.USER,
                USER.id(),
                null,
                "core",
                MemoryRetrieval.ARCHIVE),
            save(
                "항상 주입 경계",
                MemoryScope.USER,
                USER.id(),
                null,
                "core",
                MemoryRetrieval.ALWAYS),
            save(
                "대체됨 경계",
                MemoryScope.USER,
                USER.id(),
                null,
                "core",
                MemoryRetrieval.SEARCH));
        MemorySupersedeRows.supersede(jdbc, hidden.get(8).id(), own.id());
        jdbc.update(
            "UPDATE memory SET status = 'REJECTED' WHERE id = ?",
            hidden.get(4).id());
        jdbc.update(
            "UPDATE memory SET entry_type = 'SOURCE' WHERE id = ?",
            hidden.get(5).id());
        // 경계 밖 항목의 본문에도 제목과 같은 낱말을 둔다
        for (Memory excluded : hidden) {
            jdbc.update("UPDATE memory SET content = ? WHERE id = ?", excluded.title() + " 본문", excluded.id());
        }
        assertThat(search(USER, CORE, "일치", 50).items())
            .extracting(MemorySearchItem::id)
            .containsExactly(own.id(), group.id());
        for (Memory excluded : hidden) {
            assertThat(search(USER, CORE, excluded.title(), 50).items())
                .as(excluded.title())
                .isEmpty();
        }
        assertThat(search(USER, MemoryAccess.none(), "일치", 10).items())
            .isEmpty();
        CurrentUser noGroup = new CurrentUser(USER.id(), USER.email(), USER.displayName(), null, USER.role());
        assertThat(search(noGroup, CORE, "일치", 10).items())
            .extracting(MemorySearchItem::id)
            .containsExactly(own.id()); // 모든 항목에 질문과 같은 벡터를 넣어도 경계 밖 항목은 의미 점수로도 나오지 않는다
        float[] query = {1f, 0f};
        for (Memory each : repository.findAll()) {
            embedEverything(each, query);
        }
        for (Memory excluded : hidden) {
            assertThat(memories.searchFor(USER, CORE, excluded.title(), 50, MemorySearchCursor.first(excluded.title()), Optional.of(query))
                .items())
                    .as("벡터를 넣은 " + excluded.title())
                    .extracting(MemorySearchItem::id)
                    .doesNotContainAnyElementsOf(hidden.stream().map(Memory::id).toList())
                    .containsExactlyInAnyOrder(own.id(), group.id());
        }
        MemoryEmbeddingRows.clearAll(jdbc);
        vectorCache.clear();
    }


    /** 지금 글의 지문으로 벡터 줄을 넣는다. 검사 설정은 임베딩이 꺼져 있어 모델 이름이 빈 값이다. */
    private void embedEverything(Memory memory, float[] vector) {
        String content = jdbc.queryForObject("SELECT content FROM memory WHERE id = ?", String.class, memory.id());
        String text = EmbeddingText.of(memory.title(), content, embeddingProperties.maxTextChars());
        MemoryEmbeddingRows.write(jdbc, memory.id(), embeddingProperties.model(), texts.embeddingFingerprint(memory.scope(), memory.stored().ownerId(), embeddingProperties.model(), text).orElseThrow(), vector);
    }


    @Test
    @DisplayName("퍼센트 밑줄 역슬래시는 낱말을 가를 뿐이며 한글과 대소문자와 빈 결과와 잘못된 인자를 처리한다")
    void treatsLikeMetacharactersAsSeparators() {
        for (String title : List.of("100% 합성", "a_b 합성", "일반 한글", "Mixed CASE 합성")) {
            save(title);
        }
        for (String query : List.of("%", "_", "\\")) {
            assertThat(search(USER, CORE, query, 10)).isEqualTo(new MemorySearchPage(List.of(), null, MemoryRelevance.SEARCH_KEYWORD));
        }
        for (String query : List.of("100%", "한글", "  mixed case  ")) {
            assertThat(search(USER, CORE, query, 10).items()).as(query).hasSize(1);
        }
        assertThat(search(USER, CORE, "불일치", 10)).isEqualTo(new MemorySearchPage(List.of(), null, MemoryRelevance.SEARCH_KEYWORD));
        for (String invalid : List.of(" ", "가".repeat(201))) {
            assertThatThrownBy(() -> memories.searchFor(USER, CORE, invalid, 10, MemorySearchCursor.first(invalid), Optional.empty()))
                .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> search(USER, CORE, "합성", 51)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> memories.searchFor(USER, CORE, "합성", 10, null, Optional.empty())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> memories.searchFor(USER, CORE, "합성", 10, MemorySearchCursor.first("다른 질문"), Optional.empty()))
            .isInstanceOf(RuntimeException.class);
    }


    @Test
    @DisplayName("검색은 후보를 질의 하나로 읽고 푼 본문을 점수에 넘기며 외부 timeout을 물려받지 않는다")
    void selectsOnlySearchFieldsWithinIndependentTimeout() {
        Memory plain = repository.save(Memory.accepted(
            MemoryScope.USER,
            USER.id(),
            null,
            "합성 큰 본문",
            "가".repeat(16000),
            MemoryPlacement.core(MemoryRetrieval.SEARCH),
            USER.id(),
            NOW));
        Memory sealed = save(
            "합성 짧은 본문", MemoryScope.USER, USER.id(), null, "core", MemoryRetrieval.SEARCH);
        MemorySearchSqlProbe.capture();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setTimeout(30);
        MemorySearchPage page = outer.execute(
            status -> search(USER, MemoryAccess.of(Set.of("core")), "합성", 1));
        // 본문이 짧은 쪽의 BM25 가 높다
        assertThat(page.items()).extracting(MemorySearchItem::id).containsExactly(sealed.id());
        assertThat(page.nextCursor()).isNotNull();
        assertThat(MemorySearchCursor.parse(page.nextCursor())).hasValue(new MemorySearchCursor(MemorySearchCursor.fingerprintOf("합성"), 1));
        assertProjectionAndTimeout();
        MemorySearchSqlProbe.reset();
        MemorySearchSqlProbe.capture();
        assertThat(search(USER, MemoryAccess.of(Set.of("core")), "합성", 1, page.nextCursor()).items())
            .extracting(MemorySearchItem::id)
            .containsExactly(plain.id());
        MemorySearchSqlProbe.reset();
        MemorySearchSqlProbe.capture();
        repository.findAll();
        assertThat(MemorySearchSqlProbe.timeout()).isEqualTo(0);
    }


    @Test
    @DisplayName("같은 점수의 항목은 커서로 끝까지 누락과 중복 없이 읽고 200번째에서 끝난다")
    void pagesEqualScoresUntilMaxRank() {
        for (int count : List.of(200, 250)) {
            repository.deleteAll();
            List<Memory> input = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                input.add(Memory.accepted(
                    MemoryScope.USER,
                    USER.id(),
                    null,
                    "같은 점수 항목",
                    "같은 본문",
                    MemoryPlacement.core(MemoryRetrieval.SEARCH),
                    USER.id(),
                    NOW));
            }
            List<Long> expected = repository.saveAll(input).stream().map(Memory::id).sorted().toList();
            List<Long> actual = new ArrayList<>();
            String cursor = null;
            do {
                MemorySearchPage page = search(USER, CORE, "같은 점수", 50, cursor);
                assertThat(page.items()).hasSizeLessThanOrEqualTo(50);
                actual.addAll(page.items().stream().map(MemorySearchItem::id).toList());
                cursor = page.nextCursor();
            } while (cursor != null);
            assertThat(actual).as("%d건", count)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(expected.subList(0, MemorySearchCursor.MAX_RANK));
        }
    }


    @Test
    @DisplayName("50 500 5000건 자료에서 본문에만 있는 사실을 첫 결과로 찾고 지연을 잰다")
    void measuresSyntheticRecallAndLatency() {
        for (int count : List.of(50, 500, 5000)) {
            repository.deleteAll();
            List<Memory> input = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                input.add(Memory.accepted(
                    MemoryScope.USER,
                    USER.id(),
                    null,
                    "합성 색인 " + i,
                    "본문에만 있는 단어 " + "가".repeat(220),
                    MemoryPlacement.core(MemoryRetrieval.SEARCH),
                    USER.id(),
                    NOW));
            }
            List<Long> saved = repository.saveAll(input).stream().map(Memory::id).toList();
            Long target = saved.get(count / 2);
            jdbc.update("UPDATE memory SET content = ? WHERE id = ?", "타이어는 작년 11월에 네 짝을 갈았다", target);
            List<Double> elapsed = new ArrayList<>();
            long heapBefore = usedHeap();
            int chars = 0;
            for (int i = 0; i < 30; i++) {
                long started = System.nanoTime();
                MemorySearchPage page = search(USER, CORE, "타이어 언제 갈았지", 10);
                elapsed.add((System.nanoTime() - started) / 1_000_000.0);
                chars = ExternalData.wrap(JsonMapper.builder().build().writeValueAsString(page))
                    .length();
                assertThat(page.items()).extracting(MemorySearchItem::id).containsExactly(target);
            }
            long heapDelta = usedHeap() - heapBefore;
            MemorySearchPage bodyOnly = search(USER, CORE, "본문에만 있는 단어", 10);
            assertThat(bodyOnly.items()).hasSize(10);
            assertThat(bodyOnly.nextCursor()).isNotNull();
            elapsed.sort(Comparator.naturalOrder());
            System.out.printf(
                Locale.ROOT,
                "memory-search count=%d bodyOnlyRecall@1=1.0000 resultChars=%d p50ms=%.3f p95ms=%.3f heapDelta=%d%n",
                count,
                chars,
                elapsed.get(14),
                elapsed.get(28),
                heapDelta);
        }
    }


    @Test
    @DisplayName("합쳐 매긴 검색의 다음 페이지를 키워드로만 매기게 되면 순위가 달라지므로 거절한다")
    void rejectsPageWhenRankingRuleChanges() {
        save("대상 첫째");
        save("대상 둘째");
        MemorySearchCursor hybridNext = new MemorySearchCursor(MemorySearchCursor.fingerprintOf("대상"), 1, true);

        assertThatThrownBy(() -> memories.searchFor(USER, CORE, "대상", 1, hybridNext, Optional.empty()))
            .isInstanceOf(RuntimeException.class);
        assertThat(search(USER, CORE, "대상", 1).nextCursor())
            .satisfies(next -> assertThat(MemorySearchCursor.parse(next).orElseThrow().hybrid()).isFalse());
    }


    @Test
    @DisplayName("페이지 사이의 삭제는 다음 페이지에 반영되고 바뀐 항목은 처음부터 다시 찾는다")
    void usesCurrentRowsBetweenPages() {
        Memory earlier = save("이전 항목");
        Memory first = save("대상 첫째");
        Memory second = save("대상 둘째");
        MemorySearchPage page = search(USER, CORE, "대상", 1);
        assertThat(page.items()).extracting(MemorySearchItem::id).containsExactly(first.id());
        repository.deleteById(second.id());
        assertThat(search(USER, CORE, "대상", 1, page.nextCursor()))
            .isEqualTo(new MemorySearchPage(List.of(), null, MemoryRelevance.SEARCH_KEYWORD));
        jdbc.update("UPDATE memory SET title = '대상 추가' WHERE id = ?", earlier.id());
        assertThat(search(USER, CORE, "대상", 10).items())
            .extracting(MemorySearchItem::id)
            .containsExactly(earlier.id(), first.id());
    }


    MemorySearchPage search(CurrentUser user, MemoryAccess access, String query, int limit) {
        return memories.searchFor(user, access, query, limit, MemorySearchCursor.first(query.strip()), Optional.empty());
    }


    MemorySearchPage search(CurrentUser user, MemoryAccess access, String query, int limit, String cursor) {
        MemorySearchCursor position = cursor == null
            ? MemorySearchCursor.first(query.strip())
            : MemorySearchCursor.parse(cursor).orElseThrow();
        return memories.searchFor(user, access, query, limit, position, Optional.empty());
    }


    Memory save(String title) {
        return save(title, MemoryScope.USER, USER.id(), null, "core", MemoryRetrieval.SEARCH);
    }


    private Memory save(
        String title,
        MemoryScope scope,
        Long ownerId,
        Long groupId,
        String collection,
        MemoryRetrieval retrieval) {
        String body = "합성 본문";
        return repository.save(Memory.accepted(
            scope,
            ownerId,
            groupId,
            title,
            body,
            new MemoryPlacement(collection, retrieval),
            USER.id(),
            NOW));
    }


    /**
     * 후보는 질의 하나로 읽는다. 제목과 본문은 암호문이라 엔티티를 읽어 앱에서 푼다
     * (ADR-20261103 / memory-encryption).
     */
    static void assertProjectionAndTimeout() {
        assertThat(MemorySearchSqlProbe.selects()).hasSize(1);
        String select = MemorySearchSqlProbe.selects().getFirst();
        assertThat(select)
            .contains("order by")
            .doesNotContain("count(");
        assertThat(MemorySearchSqlProbe.timeout()).isBetween(1, 2);
        assertThat(MemorySearchSqlProbe.readOnly()).isTrue();
    }


    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}
