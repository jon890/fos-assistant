package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.MemorySearchItem;
import com.bifos.assistant.memory.application.model.MemorySearchPage;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.MemorySearchSqlProbe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

/** 실제 repository의 제목 검색, 권한 경계와 SQL 투영을 확인한다. 원문은 출력하지 않는다. */
@BackendIntegrationTest
public class MemorySearchTest {
    static final CurrentUser USER = new CurrentUser(811L, "search@example.test", "검색 검사", 10L, UserRole.ADMIN);
    static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"), Set.of());
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        MemorySearchSqlProbe.reset();
        repository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        MemorySearchSqlProbe.reset();
    }

    @Test
    @DisplayName("권한 상태 종류와 꺼내는 방식 밖의 항목은 제목도 검색되지 않는다")
    void excludesEveryUnreadableBoundary() {
        Memory own = save(
                "일치 개인", MemoryScope.USER, USER.id(), null, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        Memory group = save(
                "일치 그룹",
                MemoryScope.GROUP,
                null,
                USER.groupId(),
                "core",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.NORMAL);
        List<Memory> hidden = List.of(
                save("다른 사용자", MemoryScope.USER, 812L, null, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL),
                save("다른 그룹", MemoryScope.GROUP, null, 20L, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL),
                save(
                        "다른 collection",
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "career",
                        MemoryRetrieval.SEARCH,
                        MemorySensitivity.NORMAL),
                save(
                        "민감 제한",
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "core",
                        MemoryRetrieval.SEARCH,
                        MemorySensitivity.SENSITIVE),
                repository.save(Memory.proposedUser(USER.id(), "승인 전", "합성", 1L, null, NOW)),
                save(
                        "거절됨",
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "core",
                        MemoryRetrieval.SEARCH,
                        MemorySensitivity.NORMAL),
                save(
                        "출처 원문",
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "core",
                        MemoryRetrieval.SEARCH,
                        MemorySensitivity.NORMAL),
                save(
                        "보관됨",
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "core",
                        MemoryRetrieval.ARCHIVE,
                        MemorySensitivity.NORMAL),
                save(
                        "항상 주입",
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "core",
                        MemoryRetrieval.ALWAYS,
                        MemorySensitivity.NORMAL));
        jdbc.update(
                "UPDATE memory SET status = 'REJECTED' WHERE id = ?",
                hidden.get(5).id());
        jdbc.update(
                "UPDATE memory SET entry_type = 'SOURCE' WHERE id = ?",
                hidden.get(6).id());
        assertThat(memories.searchFor(USER, CORE, "일치", 50, null).items())
                .extracting(MemorySearchItem::id)
                .containsExactly(own.id(), group.id());
        for (Memory excluded : hidden) {
            assertThat(memories.searchFor(USER, CORE, excluded.title(), 50, null)
                            .items())
                    .isEmpty();
        }
        assertThat(memories.searchFor(USER, MemoryAccess.none(), "일치", 10, null).items())
                .isEmpty();
        assertThat(memories.searchFor(USER, MemoryAccess.of(Set.of("core"), Set.of("core")), "민감 제한", 10, null)
                        .items())
                .extracting(MemorySearchItem::id)
                .containsExactly(hidden.get(3).id());
        CurrentUser noGroup = new CurrentUser(USER.id(), USER.email(), USER.displayName(), null, USER.role());
        assertThat(memories.searchFor(noGroup, CORE, "일치", 10, null).items())
                .extracting(MemorySearchItem::id)
                .containsExactly(own.id());
    }

    @Test
    @DisplayName("퍼센트 밑줄 역슬래시는 글자이며 한글과 대소문자와 빈 결과를 처리한다")
    void treatsLikeMetacharactersLiterally() {
        for (String title : List.of("100% 합성", "a_b 합성", "a\\b 합성", "일반 한글", "Mixed CASE 합성")) {
            save(title);
        }
        for (String query : List.of("%", "_", "\\", "한글", "  mixed case  ")) {
            assertThat(memories.searchFor(USER, CORE, query, 10, null).items()).hasSize(1);
        }
        assertThat(memories.searchFor(USER, CORE, "불일치", 10, null)).isEqualTo(new MemorySearchPage(List.of(), null));
        for (String invalid : List.of(" ", "가".repeat(201))) {
            assertThatThrownBy(() -> memories.searchFor(USER, CORE, invalid, 10, null))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> memories.searchFor(USER, CORE, "합성", 51, null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> memories.searchFor(USER, CORE, "합성", 10, 0L)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("검색은 큰 평문과 암호문 없이 네 칸과 limit만 읽고 외부 timeout을 물려받지 않는다")
    void selectsOnlySearchFieldsWithinIndependentTimeout() {
        Memory plain = repository.save(Memory.accepted(
                MemoryScope.USER,
                USER.id(),
                null,
                "합성 큰 본문",
                StoredContent.plain("가".repeat(16000)),
                MemoryPlacement.core(MemoryRetrieval.SEARCH),
                USER.id(),
                NOW));
        save("합성 암호문", MemoryScope.USER, USER.id(), null, "core", MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        MemorySearchSqlProbe.capture();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setTimeout(30);
        MemorySearchPage page = outer.execute(
                status -> memories.searchFor(USER, MemoryAccess.of(Set.of("core"), Set.of("core")), "합성", 1, null));
        assertThat(page.items()).extracting(MemorySearchItem::id).containsExactly(plain.id());
        assertThat(page.nextAfterId()).isEqualTo(plain.id());
        assertProjectionAndTimeout();
        MemorySearchSqlProbe.reset();
        MemorySearchSqlProbe.capture();
        repository.findAll();
        assertThat(MemorySearchSqlProbe.timeout()).isEqualTo(0);
    }

    @Test
    @DisplayName("50 500 5000건 자료의 모든 번호 페이지는 순서 중복 누락 없이 제목 정답에 닿는다")
    void measuresSyntheticRecallAndPages() {
        for (int count : List.of(50, 500, 5000)) {
            repository.deleteAll();
            List<Memory> input = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                input.add(Memory.accepted(
                        MemoryScope.USER,
                        USER.id(),
                        null,
                        "합성 색인 " + i,
                        StoredContent.plain("본문에만 있는 단어 " + "가".repeat(220)),
                        MemoryPlacement.core(MemoryRetrieval.SEARCH),
                        USER.id(),
                        NOW));
            }
            List<Long> expected =
                    repository.saveAll(input).stream().map(Memory::id).toList();
            List<Long> actual = new ArrayList<>();
            Long after = null;
            do {
                MemorySearchPage page = memories.searchFor(USER, CORE, "합성 색인", 50, after);
                assertThat(page.items()).hasSizeLessThanOrEqualTo(50);
                actual.addAll(page.items().stream().map(MemorySearchItem::id).toList());
                after = page.nextAfterId();
            } while (after != null);
            assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
            List<Double> elapsed = new ArrayList<>();
            long heapBefore = usedHeap();
            int chars = 0;
            for (int i = 0; i < 30; i++) {
                long started = System.nanoTime();
                MemorySearchPage page = memories.searchFor(USER, CORE, "합성 색인", 10, null);
                elapsed.add((System.nanoTime() - started) / 1_000_000.0);
                chars = ExternalData.wrap(JsonMapper.builder().build().writeValueAsString(page))
                        .length();
                assertThat(page.items())
                        .extracting(MemorySearchItem::id)
                        .containsExactlyElementsOf(expected.subList(0, 10));
            }
            long heapDelta = usedHeap() - heapBefore;
            double recall5 =
                    memories.searchFor(USER, CORE, "합성 색인", 5, null).items().size() / (double) count;
            double recall10 = 10.0 / count;
            assertThat(recall5).isEqualTo(5.0 / count);
            assertThat(memories.searchFor(USER, CORE, "본문에만 있는 단어", 10, null).items())
                    .isEmpty();
            elapsed.sort(Comparator.naturalOrder());
            System.out.printf(
                    Locale.ROOT,
                    "memory-search count=%d recall@5=%.4f recall@10=%.4f miss@10=%.4f pageMiss=0.0000 resultChars=%d p50ms=%.3f p95ms=%.3f heapDelta=%d bodyOnlyRecall=0%n",
                    count,
                    recall5,
                    recall10,
                    1 - recall10,
                    chars,
                    elapsed.get(14),
                    elapsed.get(28),
                    heapDelta);
        }
    }

    @Test
    @DisplayName("페이지 사이의 삭제는 다음 페이지에 반영되고 지난 번호의 새 일치는 재검색으로 찾는다")
    void usesCurrentRowsBetweenPages() {
        Memory earlier = save("이전 항목");
        Memory first = save("대상 하나");
        Memory second = save("대상 둘");
        MemorySearchPage page = memories.searchFor(USER, CORE, "대상", 1, null);
        assertThat(page.nextAfterId()).isEqualTo(first.id());
        repository.deleteById(second.id());
        jdbc.update("UPDATE memory SET title = '대상 추가' WHERE id = ?", earlier.id());
        assertThat(memories.searchFor(USER, CORE, "대상", 1, page.nextAfterId()).items())
                .isEmpty();
        assertThat(memories.searchFor(USER, CORE, "대상", 10, null).items())
                .extracting(MemorySearchItem::id)
                .containsExactly(earlier.id(), first.id());
    }

    Memory save(String title) {
        return save(title, MemoryScope.USER, USER.id(), null, "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
    }

    private Memory save(
            String title,
            MemoryScope scope,
            Long ownerId,
            Long groupId,
            String collection,
            MemoryRetrieval retrieval,
            MemorySensitivity sensitivity) {
        StoredContent body = sensitivity == MemorySensitivity.SENSITIVE
                ? new StoredContent("synthetic-ciphertext", "synthetic-key")
                : StoredContent.plain("합성 본문");
        return repository.save(Memory.accepted(
                scope,
                ownerId,
                groupId,
                title,
                body,
                new MemoryPlacement(collection, retrieval, sensitivity),
                USER.id(),
                NOW));
    }

    static void assertProjectionAndTimeout() {
        assertThat(MemorySearchSqlProbe.selects()).hasSize(1);
        String select = MemorySearchSqlProbe.selects().getFirst();
        String fields = select.substring(0, select.indexOf(" from "));
        assertThat(fields.split(",")).hasSize(4);
        assertThat(fields)
                .contains(".id", ".title", ".revision", ".updated_at")
                .doesNotContain("content", "owner", "count(", "key_id");
        assertThat(select)
                .contains("order by")
                .containsAnyOf("fetch first", "limit")
                .doesNotContain("count(");
        assertThat(MemorySearchSqlProbe.timeout()).isBetween(1, 2);
        assertThat(MemorySearchSqlProbe.readOnly()).isTrue();
    }

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}
