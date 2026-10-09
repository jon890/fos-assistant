package com.bifos.assistant.context.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.context.ContextBodyMode;
import com.bifos.assistant.context.ContextItem;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Case;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Filler;
import com.bifos.assistant.context.eval.MemoryEvalDataset.Forbidden;
import com.bifos.assistant.context.eval.MemoryEvalDataset.MemorySeed;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.CaseVerdict;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.ItemState;
import com.bifos.assistant.context.eval.MemoryEvalScoreboard.ItemVerdict;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가상 가족 시험 세트를 {@link ContextAssembler} 로 조립해 Memory 회수를 측정한다. 방법과 지표는 {@code backend/docs/flow.md} 의
 * 「합성 측정」 이 갖는다.
 *
 * <p>모델도 Hermes 대역도 부르지 않는다. 조립한 문맥 묶음에서 항목마다 상태를 읽어 판정한다. 실패 조건은 권한 경계 노출 하나이고, 나머지 지표는
 * 보고서에만 남긴다.
 */
@BackendIntegrationTest
class MemoryRecallEvalTest {

    private static final MemoryEvalDataset DATASET = MemoryEvalDataset.load();
    private static final Path REPORT_DIR = Path.of("build", "reports", "memory-eval");
    private static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");
    private static final String AGENT_CODE_PREFIX = "memory-eval-";
    private static final String FACTS_OFF = "factsOff";
    private static final String FACTS_ON = "factsOn";

    /** 사용자 번호와 그룹 번호가 다른 시험이나 운영 줄과 겹치지 않게 큰 수에서 시작한다. */
    private static final long USER_ID_BASE = 910_000L;

    private static final long GROUP_ID_BASE = 920_000L;

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

    private final List<Long> createdAgentIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        memories.deleteAll();
        for (Long agentId : createdAgentIds) {
            jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", agentId);
            agents.deleteById(agentId);
        }
        createdAgentIds.clear();
    }

    @Test
    @DisplayName("시험 세트 전체를 조립해 지표를 내고 권한 경계 노출이 0 임을 확인한다")
    void measuresRecallAndKeepsBoundary() {
        clock.set(BASE);
        Map<String, Long> agentIds = createAgents();
        Map<String, ContextAssembler> assemblers = new LinkedHashMap<>();
        assemblers.put(FACTS_OFF, new ContextAssembler(memoryService, withFactsMaxChars(0), clock));
        assemblers.put(FACTS_ON, new ContextAssembler(memoryService, properties, clock));
        MemoryEvalScoreboard scoreboard = new MemoryEvalScoreboard(DATASET.version());

        for (Case each : DATASET.cases()) {
            assemblers.forEach((mode, modeAssembler) -> {
                Map<String, Long> ids = seed(each);
                AssembledContext context = modeAssembler.assemble(userOf(each.asker()), agentIds.get(each.agent()));
                scoreboard.add(mode, verdictOf(each, ids, context));
            });
        }

        String markdown = scoreboard.markdown();
        writeReport(markdown, scoreboard.json());
        assertThat(scoreboard.boundaryViolations())
                .as("권한 경계를 넘어 문맥 묶음에 든 항목(모드 사례 항목 상태)")
                .isEmpty();
        assertThat(markdown).startsWith(MemoryEvalScoreboard.HEADLINE);
        assertThat(scoreboard.metrics(FACTS_OFF, null).cases())
                .isEqualTo(DATASET.cases().size());
        assertThat(scoreboard.metrics(FACTS_ON, null).cases())
                .isEqualTo(DATASET.cases().size());
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
        assertThatThrownBy(() ->
                        MemoryEvalDataset.parse(VALID.replace("\"category\": \"BOUNDARY\"", "\"category\": \"NOPE\"")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MemoryEvalDataset.parse("{ not json")).isInstanceOf(IllegalStateException.class);
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
                                new ItemVerdict("m1", MemoryEvalDataset.ForbiddenKind.BOUNDARY, ItemState.OMITTED),
                                new ItemVerdict("m2", MemoryEvalDataset.ForbiddenKind.BOUNDARY, ItemState.ABSENT)),
                        0,
                        0));

        MemoryEvalScoreboard.Metrics metrics = scoreboard.metrics(FACTS_OFF, MemoryEvalDataset.Category.BOUNDARY);

        assertThat(metrics.recallInline().rate()).isNull();
        assertThat(metrics.boundaryExposure()).isEqualTo(1);
        assertThat(scoreboard.boundaryViolations()).containsExactly("factsOff X-01 m1 OMITTED");
        assertThat(scoreboard.markdown()).contains("해당 없음");
    }

    /** 사례의 Memory 를 새로 넣고 사례 안의 key 마다 저장된 번호를 돌려준다. */
    private Map<String, Long> seed(Case each) {
        memories.deleteAll();
        Instant now = clock.instant();
        if (each.filler() != null) {
            memories.saveAll(fillerOf(each.filler(), now));
        }
        Map<String, Long> ids = new HashMap<>();
        for (MemorySeed seed : each.memories()) {
            ids.put(seed.key(), memories.save(memoryOf(seed, now)).id());
        }
        return ids;
    }

    /** 보조 항목은 사례 항목보다 먼저 넣는다. 번호가 작은 쪽이 먼저 실리므로 사례 항목이 가장 불리한 자리에 선다. */
    private List<Memory> fillerOf(Filler filler, Instant now) {
        Instant at = now.minus(Duration.ofDays(filler.updatedDaysAgo()));
        Long owner = userOf(filler.owner()).id();
        List<Memory> rows = new ArrayList<>();
        for (int n = 1; n <= filler.count(); n++) {
            rows.add(Memory.accepted(
                    MemoryScope.USER,
                    owner,
                    null,
                    "보조 사실 " + n,
                    StoredContent.plain("가".repeat(filler.contentChars())),
                    MemoryPlacement.core(MemoryRetrieval.SEARCH),
                    owner,
                    at));
        }
        return rows;
    }

    private Memory memoryOf(MemorySeed seed, Instant now) {
        Instant at = now.minus(Duration.ofDays(seed.updatedDaysAgo()));
        StoredContent body = StoredContent.plain(seed.content());
        MemoryPlacement placement = new MemoryPlacement(seed.collection(), seed.retrieval(), seed.sensitivity());
        boolean group = seed.scope() == MemoryScope.GROUP;
        Long ownerId = group ? null : userOf(seed.owner()).id();
        Long groupId = group ? groupIdOf(seed.group()) : null;
        if (seed.entryType() == MemoryEntryType.DOCUMENT) {
            return Memory.document(
                    ownerId, seed.collection(), seed.documentKey(), seed.title(), body, seed.sensitivity(), at);
        }
        return switch (seed.status()) {
            case ACCEPTED ->
                Memory.accepted(
                        seed.scope(),
                        ownerId,
                        groupId,
                        seed.title(),
                        body,
                        placement,
                        group ? firstUserIdOfGroup(seed.group()) : ownerId,
                        at);
            case PROPOSED -> Memory.proposed(ownerId, seed.title(), body, placement, null, null, at);
            case REJECTED -> {
                Memory proposed = Memory.proposed(ownerId, seed.title(), body, placement, null, null, at);
                proposed.reject(at);
                yield proposed;
            }
        };
    }

    private CaseVerdict verdictOf(Case each, Map<String, Long> ids, AssembledContext context) {
        List<ItemVerdict> items = new ArrayList<>();
        for (String key : each.expect()) {
            items.add(new ItemVerdict(key, null, stateOf(ids.get(key), context)));
        }
        for (Forbidden forbidden : each.forbidden()) {
            items.add(new ItemVerdict(
                    forbidden.memory(), forbidden.kind(), stateOf(ids.get(forbidden.memory()), context)));
        }
        return new CaseVerdict(each.id(), each.category(), items, context.chars(), context.omittedItems());
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

    /** 에이전트를 저장하고 시험 세트가 적은 collection 만 받게 한다. 저장하면 딸려 오는 core 줄은 지우고 다시 넣는다. */
    private Map<String, Long> createAgents() {
        Map<String, Long> agentIds = new LinkedHashMap<>();
        for (MemoryEvalDataset.Agent each : DATASET.agents()) {
            String code = AGENT_CODE_PREFIX + each.key();
            Long agentId = agents.findByCode(code)
                    .orElseGet(() -> agents.save(Agent.of(
                            code,
                            "회수 측정 " + each.key(),
                            code,
                            "http://runtime.test/p/" + code,
                            CostMode.API,
                            CredentialScope.DEDICATED,
                            AgentVisibility.GROUP,
                            null,
                            BASE)))
                    .id();
            createdAgentIds.add(agentId);
            jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", agentId);
            each.collections()
                    .forEach(grant -> grants.save(
                            AgentMemoryCollection.of(agentId, grant.collection(), grant.allowSensitive(), BASE)));
            agentIds.put(each.key(), agentId);
        }
        return agentIds;
    }

    private static CurrentUser userOf(String key) {
        MemoryEvalDataset.User user = DATASET.user(key);
        int index = DATASET.users().indexOf(user);
        return new CurrentUser(USER_ID_BASE + index, key + "@example.com", key, groupIdOf(user.group()), user.role());
    }

    private static Long groupIdOf(String group) {
        List<String> groups = DATASET.users().stream()
                .map(MemoryEvalDataset.User::group)
                .distinct()
                .toList();
        return GROUP_ID_BASE + groups.indexOf(group);
    }

    private static Long firstUserIdOfGroup(String group) {
        return DATASET.users().stream()
                .filter(user -> user.group().equals(group))
                .findFirst()
                .map(user -> userOf(user.key()).id())
                .orElseThrow();
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
                  "forbidden": []
                }
              ]
            }
            """;
}
