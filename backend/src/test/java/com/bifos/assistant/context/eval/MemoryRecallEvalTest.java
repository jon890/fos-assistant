package com.bifos.assistant.context.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.context.application.dto.AssembledContext;
import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.context.application.dto.ContextBodyMode;
import com.bifos.assistant.context.application.dto.ContextItem;
import com.bifos.assistant.context.config.ContextProperties;
import com.bifos.assistant.context.application.dto.ContextSource;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Case;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Forbidden;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.CaseVerdict;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.ItemState;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.ItemVerdict;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TestContextAssemblers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가상 가족 시험 세트를 {@link ContextAssembler} 로 조립해 Memory 회수를 측정한다. 방법과 지표는 {@code docs/features/memory.md} 의
 * 「합성 측정」 이 갖는다.
 *
 * <p>모델도 Hermes 대역도 부르지 않는다. 조립한 문맥 묶음에서 항목마다 상태를 읽어 판정한다. 실패 조건은 권한 경계 노출, 기준 파일보다 나빠진
 * 지표, 질문을 넘긴 조립의 {@code RECALL_GAP} recall@5 가 질문을 넘기지 않은 조립보다 높지 않은 것, 대체 연결을 넣은 조립의
 * {@code KNOWLEDGE_UPDATE} 오기억 노출률이 연결 없는 조립보다 낮지 않은 것, 관련 연결을 넣은 조립의 {@code MULTI_SESSION} recall@5 가 연결 없는
 * 조립보다 높지 않은 것이다. 나머지 지표는 보고서에만 남긴다.
 */
@BackendIntegrationTest
class MemoryRecallEvalTest {

    private static final MemoryEvalDataset DATASET = MemoryEvalDataset.load();
    private static final Path REPORT_DIR = Path.of("build", "reports", "memory-eval");
    private static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");
    private static final String NONE = "none";
    private static final String QUESTION_OFF = "questionOff";
    private static final String FACTS_OFF = "factsOff";
    private static final String FACTS_ON = "factsOn";
    private static final String LINKS_OFF = "linksOff";
    private static final String FULL_INJECTION = "fullInjection";

    @Autowired
    MemoryService memoryService;

    @Autowired
    ContextProperties properties;

    @Autowired
    MemoryRepository memories;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestClock clock;

    private MemoryEvalFixture fixture;


    @BeforeEach
    void setUp() {
        fixture = new MemoryEvalFixture(DATASET, memories, agents, grants, jdbc, clock);
    }


    @AfterEach
    void tearDown() {
        fixture.tearDown();
    }


    @Test
    @DisplayName("시험 세트 전체를 조립해 지표를 내고 권한 경계 노출이 0 임을 확인한다")
    void measuresRecallAndKeepsBoundary() {
        clock.set(BASE);
        Map<String, Long> agentIds = fixture.createAgents();
        ContextAssembler factsOn = TestContextAssemblers.of(memoryService, properties, clock);
        ContextAssembler factsOff = TestContextAssemblers.of(memoryService, withFactsMaxChars(0), clock);
        // questionOff 는 질문을 넘기지 않는 지금까지의 조립이다. 질문으로 고른 구역이 놓친 사례를 줄였는지 견준다
        Map<String, Assembly> assemblies = new LinkedHashMap<>();
        assemblies.put(QUESTION_OFF, (user, agentId, question) -> factsOn.assemble(user, agentId));
        assemblies.put(FACTS_OFF, factsOff::assemble);
        assemblies.put(FACTS_ON, factsOn::assemble);
        // linksOff 는 factsOn 과 같은 조립에 대체 연결만 넣지 않은 단계 1 의 구조다. 연결이 옛 사실의 노출을 줄였는지 견준다
        assemblies.put(LINKS_OFF, factsOn::assemble);
        MemoryEvalScoreboard scoreboard = new MemoryEvalScoreboard(DATASET.version());

        for (Case each : DATASET.cases()) {
            scoreboard.add(NONE, noneVerdictOf(each));
            assemblies.forEach((mode, assembly) -> {
                Map<String, Long> ids = fixture.seed(each, !LINKS_OFF.equals(mode));
                AssembledContext context = assembly.assemble(
                    fixture.userOf(each.asker()), agentIds.get(each.agent()), each.question());
                scoreboard.add(mode, verdictOf(each, ids, context));
            });
            Map<String, Long> ids = fixture.seed(each);
            scoreboard.add(FULL_INJECTION, fullInjectionVerdictOf(each, ids, agentIds.get(each.agent())));
        }

        String markdown = scoreboard.markdown();
        writeReport(markdown, scoreboard.json());
        assertThat(scoreboard.boundaryViolations())
            .as("권한 경계를 넘어 문맥 묶음에 든 항목(모드 사례 항목 상태)")
            .isEmpty();
        assertThat(markdown).startsWith(MemoryEvalScoreboard.HEADLINE);
        for (String mode : List.of(NONE, QUESTION_OFF, FACTS_OFF, FACTS_ON, LINKS_OFF, FULL_INJECTION)) {
            assertThat(scoreboard.metrics(mode, null).cases()).as(mode).isEqualTo(DATASET.cases().size());
        }
        Double gapWithQuestion = scoreboard.metrics(FACTS_ON, MemoryEvalDataset.Category.RECALL_GAP).recallAt5().rate();
        Double gapWithoutQuestion = scoreboard.metrics(QUESTION_OFF, MemoryEvalDataset.Category.RECALL_GAP).recallAt5().rate();
        assertThat(gapWithQuestion)
            .as("RECALL_GAP recall@5: factsOn=%s questionOff=%s", gapWithQuestion, gapWithoutQuestion)
            .isNotNull()
            .isGreaterThan(gapWithoutQuestion);
        Double updateWithLinks = scoreboard.metrics(FACTS_ON, MemoryEvalDataset.Category.KNOWLEDGE_UPDATE)
            .falseMemoryExposure().rate();
        Double updateWithoutLinks = scoreboard.metrics(LINKS_OFF, MemoryEvalDataset.Category.KNOWLEDGE_UPDATE)
            .falseMemoryExposure().rate();
        assertThat(updateWithLinks)
            .as("KNOWLEDGE_UPDATE 오기억 노출률: factsOn=%s linksOff=%s", updateWithLinks, updateWithoutLinks)
            .isNotNull()
            .isLessThan(updateWithoutLinks);
        Double multiWithLinks = scoreboard.metrics(FACTS_ON, MemoryEvalDataset.Category.MULTI_SESSION).recallAt5().rate();
        Double multiWithoutLinks = scoreboard.metrics(LINKS_OFF, MemoryEvalDataset.Category.MULTI_SESSION).recallAt5().rate();
        assertThat(multiWithLinks)
            .as("MULTI_SESSION recall@5: factsOn=%s linksOff=%s", multiWithLinks, multiWithoutLinks)
            .isNotNull()
            .isGreaterThan(multiWithoutLinks);
        MemoryEvalScoreboard.Metrics factsOnMetrics = scoreboard.metrics(FACTS_ON, null);
        if (Boolean.getBoolean(MemoryEvalBaseline.WRITE_PROPERTY)) {
            MemoryEvalBaseline.of(DATASET.version(), FACTS_ON, factsOnMetrics).write(MemoryEvalBaseline.SOURCE);
            return;
        }
        assertThat(MemoryEvalBaseline.load().violations(DATASET.version(), factsOnMetrics))
            .as("합성 측정이 기준 파일보다 나빠졌다. 절차는 docs/features/memory.md 의 「기준 파일」")
            .isEmpty();
    }


    /** 모드 하나의 조립이다. 질문을 쓰지 않는 모드는 받은 질문을 버린다. */
    @FunctionalInterface
    private interface Assembly {
        AssembledContext assemble(CurrentUser user, Long agentId, String question);
    }


    /** 주입받은 설정에서 개인 사실 구역 예산만 바꾼다. 0 이면 구역을 끈다. */
    private ContextProperties withFactsMaxChars(int factsMaxChars) {
        return new ContextProperties(
            properties.maxChars(),
            properties.indexBudgetRatio(),
            properties.resultStaleAfter(),
            properties.memoryStaleAfter(),
            properties.memoryCollectionStaleAfter(),
            factsMaxChars,
            properties.factsItemMaxChars());
    }


    @Test
    @DisplayName("깨진 참조가 있는 시험 세트는 읽을 때 거절한다")
    void rejectsBrokenReferencesWhileLoading() {
        assertThat(MemoryEvalDataset.parse(VALID).cases()).hasSize(1);

        assertThatThrownBy(() -> MemoryEvalDataset.parse(VALID.replace("\"expect\": [\"m1\"]", "\"expect\": [\"zz\"]")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expect")
            .hasMessageContaining("zz");
        assertThatThrownBy(
            () -> MemoryEvalDataset.parse(VALID.replace("\"asker\": \"parentA\"", "\"asker\": \"ghost\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asker");
        assertThatThrownBy(
            () -> MemoryEvalDataset.parse(VALID.replace("\"agent\": \"general\"", "\"agent\": \"ghost\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent");
        assertThatThrownBy(() -> MemoryEvalDataset.parse(VALID.replace(
            "\"group\": \"home\" }",
            "\"group\": \"home\" }, "
                + "{ \"key\": \"parentA\", \"role\": \"MEMBER\", \"group\": \"home\" }")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("겹친다");
        assertThatThrownBy(() -> MemoryEvalDataset.parse(VALID.replace("\"owner\": \"parentA\", ", "")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owner");
        assertThatThrownBy(() -> MemoryEvalDataset.parse(VALID.replace("\"category\": \"BOUNDARY\"", "\"category\": \"NOPE\"")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MemoryEvalDataset.parse("{ not json")).isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("대체 관계와 판정 질문이 어긋난 사례 묶음은 읽을 때 거절한다")
    void rejectsBrokenSupersedesAndJudgeWhileLoading() {
        assertThat(MemoryEvalDataset.parse(SUPERSEDING).cases().getFirst().judge()).hasSize(1);

        assertRejected(SUPERSEDING.replace("\"supersedes\": \"m1\"", "\"supersedes\": \"zz\""), "supersedes");
        assertRejected(SUPERSEDING.replace("\"supersedes\": \"m1\"", "\"supersedes\": \"m2\""), "supersedes");
        assertRejected(
            SUPERSEDING.replace("{ \"key\": \"m1\", \"owner\": \"parentA\"", "{ \"key\": \"m1\", \"owner\": \"parentB\""),
            "supersedes");
        assertRejected(SUPERSEDING.replace(", \"supersedes\": \"m1\"", ""), "supersedes");
        assertRejected(
            SUPERSEDING.replace(
                "\"judge\": [ { \"id\": \"j1\", \"question\": \"옛 값을 말하는가?\", \"expect\": false } ]",
                "\"judge\": [ { \"id\": \"j1\", \"question\": \"가?\", \"expect\": false }, "
                    + "{ \"id\": \"j1\", \"question\": \"나?\", \"expect\": true } ]"),
            "judge");
    }


    private static void assertRejected(String json, String message) {
        assertThatThrownBy(() -> MemoryEvalDataset.parse(json))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(message);
    }


    @Test
    @DisplayName("분모가 0 인 비율은 해당 없음으로 내고 OMITTED 도 권한 경계 노출로 센다")
    void reportsNotApplicableAndCountsOmittedAsBoundaryExposure() {
        MemoryEvalScoreboard scoreboard = new MemoryEvalScoreboard(1);
        scoreboard.add(
            FACTS_OFF,
            new CaseVerdict(
                "X-01",
                MemoryEvalDataset.Category.BOUNDARY,
                List.of(
                    new ItemVerdict("m1", MemoryEvalDataset.ForbiddenKind.BOUNDARY, ItemState.OMITTED, null),
                    new ItemVerdict("m2", MemoryEvalDataset.ForbiddenKind.BOUNDARY, ItemState.ABSENT, null)),
                0,
                0));

        MemoryEvalScoreboard.Metrics metrics = scoreboard.metrics(FACTS_OFF, MemoryEvalDataset.Category.BOUNDARY);

        assertThat(metrics.recallInline().rate()).isNull();
        assertThat(metrics.boundaryExposure()).isEqualTo(1);
        assertThat(scoreboard.boundaryViolations()).containsExactly("factsOff X-01 m1 OMITTED");
        assertThat(scoreboard.markdown()).contains("해당 없음");
    }


    @Test
    @DisplayName("recall@k 는 질문으로 고른 항목, 나머지 본문 항목, 제목 항목의 순위로 세고 순위가 없는 항목은 세지 않는다")
    void countsRecallAtKByRank() {
        MemoryEvalScoreboard scoreboard = new MemoryEvalScoreboard(1);
        scoreboard.add(
            FACTS_ON,
            new CaseVerdict(
                "X-02",
                MemoryEvalDataset.Category.INFORMATION_EXTRACTION,
                List.of(
                    new ItemVerdict("m1", null, ItemState.INLINE, 1),
                    new ItemVerdict("m2", null, ItemState.TITLE_ONLY, 6),
                    new ItemVerdict("m3", null, ItemState.OMITTED, null)),
                0,
                1));

        MemoryEvalScoreboard.Metrics metrics = scoreboard.metrics(FACTS_ON, null);

        assertThat(metrics.recallAt5().numerator()).isEqualTo(1);
        assertThat(metrics.recallAt5().denominator()).isEqualTo(3);
        assertThat(metrics.recallAt10().numerator()).isEqualTo(2);
        assertThat(ranksOf(List.of(
            item("memory:1", ContextBodyMode.TITLE_ONLY),
            item("memory:2", ContextBodyMode.INLINE),
            item("memory:3", ContextBodyMode.OMITTED))))
                .containsExactlyInAnyOrderEntriesOf(Map.of("memory:2", 1, "memory:1", 2));
        assertThat(ranksOf(List.of(
            item("memory:1", ContextBodyMode.TITLE_ONLY),
            item("memory:2", ContextBodyMode.INLINE),
            item(ContextSource.MEMORY_SEARCH, "memory:4", ContextBodyMode.INLINE))))
                .containsExactlyInAnyOrderEntriesOf(Map.of("memory:4", 1, "memory:2", 2, "memory:1", 3));
    }


    @Test
    @DisplayName("기준 파일보다 recall@5 가 낮거나 오기억 노출률이 높거나 버전이 다르면 어긋남을 낸다")
    void reportsBaselineViolations() {
        MemoryEvalBaseline baseline = new MemoryEvalBaseline(2, FACTS_ON, 0.5, 0.25);

        assertThat(baseline.violations(2, metricsOf(2, 4, 1, 4))).isEmpty();
        assertThat(baseline.violations(2, metricsOf(1, 4, 1, 4))).singleElement().asString().contains("recall@5");
        assertThat(baseline.violations(2, metricsOf(2, 4, 2, 4))).singleElement().asString().contains("오기억 노출률");
        assertThat(baseline.violations(3, metricsOf(2, 4, 1, 4)))
            .singleElement()
            .asString()
            .contains(MemoryEvalBaseline.WRITE_PROPERTY);
    }


    /** recall@5 와 오기억 노출률만 채운 지표다. */
    private static MemoryEvalScoreboard.Metrics metricsOf(int recalled, int expected, int exposed, int falseMemories) {
        MemoryEvalScoreboard.Ratio none = MemoryEvalScoreboard.Ratio.of(0, 0);
        return new MemoryEvalScoreboard.Metrics(
            1,
            none,
            none,
            MemoryEvalScoreboard.Ratio.of(recalled, expected),
            none,
            MemoryEvalScoreboard.Ratio.of(exposed, falseMemories),
            0,
            0,
            null,
            0);
    }


    private static ContextItem item(String ref, ContextBodyMode mode) {
        return item(null, ref, mode);
    }


    private static ContextItem item(ContextSource source, String ref, ContextBodyMode mode) {
        return new ContextItem(source, ref, null, null, null, null, null, null, mode, List.of(), null, null);
    }


    static CaseVerdict verdictOf(Case each, Map<String, Long> ids, AssembledContext context) {
        Map<String, Integer> ranks = ranksOf(context.bundle().items());
        List<ItemVerdict> items = new ArrayList<>();
        for (String key : each.expect()) {
            String ref = ContextItem.memoryRef(ids.get(key));
            items.add(new ItemVerdict(key, null, stateOf(ids.get(key), context), ranks.get(ref)));
        }
        for (Forbidden forbidden : each.forbidden()) {
            String ref = ContextItem.memoryRef(ids.get(forbidden.memory()));
            items.add(new ItemVerdict(
                forbidden.memory(),
                forbidden.kind(),
                stateOf(ids.get(forbidden.memory()), context),
                ranks.get(ref)));
        }
        return new CaseVerdict(each.id(), each.category(), items, context.chars(), context.omittedItems());
    }


    /** 기억을 싣지 않는 아래쪽 기준선이다. 모든 항목이 없다. */
    private static CaseVerdict noneVerdictOf(Case each) {
        List<ItemVerdict> items = new ArrayList<>();
        each.expect().forEach(key -> items.add(new ItemVerdict(key, null, ItemState.ABSENT, null)));
        each.forbidden()
            .forEach(forbidden -> items.add(
                new ItemVerdict(forbidden.memory(), forbidden.kind(), ItemState.ABSENT, null)));
        return new CaseVerdict(each.id(), each.category(), items, 0, 0);
    }


    /**
     * 그 에이전트가 볼 수 있는 항목을 예산 없이 모두 본문으로 싣는 위쪽 기준선이다. 권한 조건은 조립과 같은 {@link MemoryService} 의 조회를
     * 쓴다. 항상 층 번호 순, 그다음 색인 번호 순으로 늘어놓는다.
     */
    private CaseVerdict fullInjectionVerdictOf(Case each, Map<String, Long> ids, Long agentId) {
        CurrentUser asker = fixture.userOf(each.asker());
        MemoryAccess access = memoryService.accessOf(agentId);
        List<Memory> visible = new ArrayList<>();
        memoryService.alwaysInjectedFor(asker, access).stream()
            .sorted(Comparator.comparing(Memory::id))
            .forEach(visible::add);
        memoryService.indexedFor(asker, access).stream()
            .sorted(Comparator.comparing(Memory::id))
            .forEach(visible::add);
        Map<Long, Integer> ranks = new HashMap<>();
        long chars = 0;
        for (Memory memory : visible) {
            ranks.putIfAbsent(memory.id(), ranks.size() + 1);
            chars += ("- [" + memory.id() + "] " + memory.title() + ": " + memory.content()).length() + 1;
        }
        List<ItemVerdict> items = new ArrayList<>();
        for (String key : each.expect()) {
            items.add(fullInjectionItem(key, null, ranks.get(ids.get(key))));
        }
        for (Forbidden forbidden : each.forbidden()) {
            items.add(fullInjectionItem(forbidden.memory(), forbidden.kind(), ranks.get(ids.get(forbidden.memory()))));
        }
        return new CaseVerdict(each.id(), each.category(), items, chars, 0);
    }


    private static ItemVerdict fullInjectionItem(String key, MemoryEvalDataset.ForbiddenKind kind, Integer rank) {
        return new ItemVerdict(key, kind, rank == null ? ItemState.ABSENT : ItemState.INLINE, rank);
    }


    /**
     * recall@k 의 순위를 매긴다. 질문으로 고른 {@code MEMORY_SEARCH} 항목을 실은 순서대로, 그 뒤에 나머지 {@code INLINE} 항목을, 그 뒤에
     * {@code TITLE_ONLY} 항목을 실은 순서대로 1 부터 센다. 빠진 항목은 순위가 없다.
     *
     * <p>질문으로 고른 항목을 앞에 두는 것은 그 구역이 관련도 순으로 고른 유일한 본문이라서다. 개인 사실 구역은 질문과 무관하게 최근 순으로 고르므로,
     * 실은 차례대로 세면 사실이 많은 사람의 질문 관련 항목이 늘 5 위 밖으로 밀린다.
     *
     * @return 항목 참조마다 순위
     */
    static Map<String, Integer> ranksOf(List<ContextItem> items) {
        Map<String, Integer> ranks = new HashMap<>();
        items.stream()
            .filter(item -> item.source() == ContextSource.MEMORY_SEARCH && item.bodyMode() == ContextBodyMode.INLINE)
            .forEach(item -> ranks.putIfAbsent(item.ref(), ranks.size() + 1));
        for (ContextBodyMode mode : List.of(ContextBodyMode.INLINE, ContextBodyMode.TITLE_ONLY)) {
            items.stream()
                .filter(item -> item.bodyMode() == mode)
                .forEach(item -> ranks.putIfAbsent(item.ref(), ranks.size() + 1));
        }
        return ranks;
    }


    /** 문맥 묶음에서 그 Memory 의 항목을 찾아 싣는 방식을 상태로 옮긴다. 글을 찾아 보지 않는다. */
    private static ItemState stateOf(Long memoryId, AssembledContext context) {
        String ref = ContextItem.memoryRef(memoryId);
        return context.bundle().items().stream()
            .filter(item -> ref.equals(item.ref()))
            .findFirst()
            .map(item -> stateOf(item.bodyMode()))
            .orElse(ItemState.ABSENT);
    }


    private static ItemState stateOf(ContextBodyMode mode) {
        return switch (mode) {
            case INLINE -> ItemState.INLINE;
            case TITLE_ONLY -> ItemState.TITLE_ONLY;
            case OMITTED -> ItemState.OMITTED;
        };
    }


    /** 보고서를 빌드 디렉터리에 남긴다. CI 로그에서도 보이도록 표준 출력에 함께 낸다. */
    private static void writeReport(String markdown, String json) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve("report.md"), markdown);
            Files.writeString(REPORT_DIR.resolve("report.json"), json);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        System.out.println(markdown);
    }


    /** 참조 검사를 확인하는 데 쓰는 가장 작은 시험 세트다. */
    private static final String VALID = """
            {
              "version": 1,
              "note": "검사용",
              "users": [ { "key": "parentA", "role": "ADMIN", "group": "home" } ],
              "agents": [ { "key": "general", "collections": [ { "collection": "core", "allowSensitive": false } ] } ],
              "cases": [
                {
                  "id": "X-01",
                  "category": "BOUNDARY",
                  "asker": "parentA",
                  "agent": "general",
                  "question": "질문",
                  "memories": [
                    { "key": "m1", "owner": "parentA", "scope": "USER", "title": "제목", "content": "내용" }
                  ],
                  "expect": ["m1"],
                  "forbidden": [],
                  "judge": [ { "id": "j1", "question": "답이 내용을 말하는가?", "expect": true } ]
                }
              ]
            }
            """;

    /** 대체 관계와 판정 질문의 검사를 확인하는 사례 묶음이다. m2 가 m1 을 대체한다. */
    private static final String SUPERSEDING = """
            {
              "version": 2,
              "note": "검사용",
              "users": [
                { "key": "parentA", "role": "ADMIN", "group": "home" },
                { "key": "parentB", "role": "ADMIN", "group": "home" }
              ],
              "agents": [ { "key": "general", "collections": [ { "collection": "core", "allowSensitive": false } ] } ],
              "cases": [
                {
                  "id": "KU-X",
                  "category": "KNOWLEDGE_UPDATE",
                  "asker": "parentA",
                  "agent": "general",
                  "question": "질문",
                  "memories": [
                    { "key": "m1", "owner": "parentA", "scope": "USER", "title": "제목", "content": "옛 값" },
                    { "key": "m2", "owner": "parentA", "scope": "USER", "title": "제목", "content": "새 값", "supersedes": "m1" }
                  ],
                  "expect": ["m2"],
                  "forbidden": [ { "memory": "m1", "kind": "SUPERSEDED" } ],
                  "judge": [ { "id": "j1", "question": "옛 값을 말하는가?", "expect": false } ]
                }
              ]
            }
            """;
}
