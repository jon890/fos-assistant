package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실행 instructions 에 들어갈 Memory 층과 권한 경계를 확인한다. */
@BackendIntegrationTest
class ContextAssemblerTest {

    private static final CurrentUser ADMIN = user(1L, 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, 10L, UserRole.MEMBER);
    private static final String AGENT_CODE = "context-assembler-test";
    private static final String FACTS_HEADER =
            "# 지금 묻는 사람에 대해 기억한 것\n\n" + "아래는 이 사람에 대해 기억한 짧은 사실이다. 필요하면 번호로 memory_read 를 불러 다시 읽는다.";
    private static final String INDEX_HEADER = "# 더 물어볼 수 있는 것";

    /** 개인 사실 구역의 본문 길이 상한(200자)을 넘어 색인에만 남는 본문이다. */
    private static final String LONG_BODY = "색".repeat(201);

    @Autowired
    ContextAssembler assembler;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    AgentRepository agents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContextProperties properties;

    @Autowired
    TestClock clock;

    /** core collection 을 받는 보통 에이전트의 번호다. 저장하면 core 가 딸려 온다(ADR-053). */
    private Long agentId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        agentId = agents.findByCode(AGENT_CODE)
                .orElseGet(() -> agents.save(Agent.of(
                        AGENT_CODE,
                        "조립 검사",
                        AGENT_CODE,
                        "http://runtime.test/p/" + AGENT_CODE,
                        CostMode.API,
                        CredentialScope.DEDICATED,
                        AgentVisibility.GROUP,
                        null,
                        Instant.now())))
                .id();
    }

    @Test
    @DisplayName("Memory 없이도 GFM 표와 로컬 MCP 단건 호출 지침과 길이와 지문을 보낸다")
    void addsResponseInstructionsWithoutMemory() {
        AssembledContext result = assembler.withResponseInstructions(AssembledContext.empty());

        assertThat(result.instructions())
                .contains(
                        "GFM",
                        "| --- | --- |",
                        "tool_call",
                        "calls 배열",
                        "name 과 arguments",
                        "정확히 한 항목",
                        "같은 서버의 읽기 도구",
                        "별도 호출",
                        "HTTP 여도",
                        "connectors__... HTTP 원격 도구 서버");
        assertThat(result.chars()).isEqualTo(result.instructions().length());
        assertThat(result.instructionsHash()).isNotNull();
    }

    @Test
    @DisplayName("기억 여부와 관계없이 로컬 MCP 단건 호출 지침을 답변 형식 뒤에 싣는다")
    void addsMemoryInstructionsOnlyWhenRemembering() {
        AssembledContext remembering = assembler.withResponseInstructions(AssembledContext.empty(), true);
        AssembledContext plain = assembler.withResponseInstructions(AssembledContext.empty(), false);

        assertThat(remembering.instructions())
                .startsWith("# 답변 형식")
                .contains(
                        "# 도구 호출",
                        "calls 배열",
                        "name 과 arguments",
                        "같은 서버의 읽기 도구",
                        "별도 호출",
                        "connectors__... HTTP 원격 도구 서버",
                        "# 기억",
                        "memory_remember",
                        "그대로 살린다",
                        "작업 기록")
                .containsSubsequence("# 답변 형식", "# 도구 호출", "# 기억");
        assertThat(plain.instructions())
                .contains(
                        "# 도구 호출",
                        "calls 배열",
                        "name 과 arguments",
                        "같은 서버의 읽기 도구",
                        "별도 호출",
                        "connectors__... HTTP 원격 도구 서버")
                .doesNotContain("# 기억", "memory_remember");
        assertThat(assembler.withResponseInstructions(AssembledContext.empty()).instructions())
                .isEqualTo(plain.instructions());
    }

    @Test
    @DisplayName("공통 지침은 Memory 예산 밖에 두고 본문과 누락 번호와 묶음을 보존한다")
    void keepsMemoryBudgetAndOmissionsIndependent() {
        String body = "가".repeat(8_000);
        ContextBundle bundle = new ContextBundle(List.of(new ContextItem(
                ContextSource.MEMORY_ALWAYS,
                "memory:3",
                MemoryScope.USER,
                ADMIN.id(),
                MemorySensitivity.NORMAL,
                ContextTrust.USER_APPROVED,
                Instant.EPOCH,
                ContextFreshness.FRESH,
                ContextBodyMode.INLINE,
                List.of(),
                "기억 제목",
                body)));
        AssembledContext memory = new AssembledContext(body, body.length(), List.of(3L), bundle);

        AssembledContext result = assembler.withResponseInstructions(memory);

        assertThat(result.instructions())
                .startsWith("# 답변 형식")
                .contains("# 도구 호출", "calls 배열", "name 과 arguments")
                .endsWith(body);
        assertThat(result.chars()).isEqualTo(result.instructions().length()).isGreaterThan(8_000);
        assertThat(result.omittedMemoryIds()).containsExactly(3L);
        assertThat(result.bundle()).isEqualTo(bundle);
        assertThat(result.instructionsHash()).isNotEqualTo(memory.instructionsHash());
    }

    @Test
    @DisplayName("지문은 앞 16바이트의 고정 값이고 지침이 비면 null 이다")
    void instructionsHashIsFixedValueAndNullWhenEmpty() {
        assertThat(new AssembledContext("지침 본문", 5).instructionsHash()).isEqualTo("fd75209d816515dfa7bffe27acfcb25f");
        assertThat(new AssembledContext("", 0).instructionsHash()).isNull();
        assertThat(AssembledContext.empty().instructionsHash()).isNull();
    }

    @Test
    @DisplayName("그룹과 개인 항목을 층 순서대로 본문까지 넣는다")
    void assemblesGroupAndUserItemsInLayerOrderWithBodies() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 제목", "그룹 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .contains("# 우리 그룹이 함께 아는 것", "그룹 내용", "# 지금 묻는 사람에 대해 아는 것", "개인 내용")
                .containsSubsequence("# 우리 그룹이 함께 아는 것", "그룹 내용", "# 지금 묻는 사람에 대해 아는 것", "개인 내용");
        assertThat(result.chars()).isEqualTo(result.instructions().length());
    }

    @Test
    @DisplayName("다른 사용자의 개인 항목과 승인 전 항목은 조립하지 않는다")
    void skipsOtherUsersPersonalItemsAndUnapprovedItems() {
        memories.create(ADMIN, MemoryScope.USER, "아빠 제목", "아빠만 아는 내용", true);
        memories.proposeUser(MEMBER, "제안 제목", "승인 전 내용", 1L);

        AssembledContext result = assembler.assemble(MEMBER, agentId);

        assertThat(result.instructions()).isNull();
        assertThat(result.chars()).isZero();
    }

    @Test
    @DisplayName("넣을 항목이 없으면 null과 0을 낸다")
    void returnsNullAndZeroWhenNothingToInsert() {
        assertThat(assembler.assemble(ADMIN, agentId)).isEqualTo(AssembledContext.empty());
    }

    @Test
    @DisplayName("한 층만 있으면 그 층의 제목만 넣는다")
    void insertsOnlyTitlesOfLayerWhenOnlyOneLayerExists() {
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .contains("# 지금 묻는 사람에 대해 아는 것", "개인 내용")
                .doesNotContain("# 우리 그룹이 함께 아는 것", "# 더 물어볼 수 있는 것");
    }

    @Test
    @DisplayName("상한을 넘는 항목은 일부도 넣지 않고 그 항목만 건너뛴다")
    void skipsOnlyItemOverLimitWithoutPartialInsert() {
        Memory first = memories.create(ADMIN, MemoryScope.GROUP, "첫째", "가".repeat(5_000), true);
        Memory second = memories.create(ADMIN, MemoryScope.GROUP, "둘째", "나".repeat(5_000), true);
        Memory third = memories.create(ADMIN, MemoryScope.GROUP, "셋째", "짧은 내용", true);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .contains(first.content(), third.content())
                .doesNotContain(second.content());
        assertThat(result.omittedMemoryIds()).containsExactly(second.id());
        assertThat(result.chars()).isEqualTo(result.instructions().length()).isLessThanOrEqualTo(8_000);
    }

    @Test
    @DisplayName("첫 항목이 상한을 넘어도 색인과 나머지 항목을 싣는다")
    void shipsIndexAndRestEvenIfFirstItemExceedsLimit() {
        Memory tooLong = memories.create(ADMIN, MemoryScope.GROUP, "너무 긴 항목", "가".repeat(9_000), true);
        Memory shortOne = memories.create(ADMIN, MemoryScope.GROUP, "짧은 항목", "짧은 내용", true);
        // 본문이 개인 사실 구역의 상한을 넘어 색인에만 오른다
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인만 하는 제목", LONG_BODY, false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .isNotNull()
                .contains("# 더 물어볼 수 있는 것", "[" + indexed.id() + "] 색인만 하는 제목", shortOne.content())
                .doesNotContain(tooLong.content());
        assertThat(result.omittedMemoryIds()).containsExactly(tooLong.id());
        assertThat(result.omittedItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("항상 층이 상한을 거의 채워도 색인 층을 싣는다")
    void shipsIndexLayerEvenWhenAlwaysLayerNearlyFillsLimit() {
        memories.create(ADMIN, MemoryScope.GROUP, "거의 상한", "가".repeat(7_960), true);
        // 짧은 본문이면 항상 층이 빠진 자리를 개인 사실 구역이 쓴다. 색인에만 오르게 해 색인이 밀려나지 않는지 본다
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", LONG_BODY, false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).isNotNull().contains("# 더 물어볼 수 있는 것", "[" + indexed.id() + "] 색인 제목");
        assertThat(result.chars()).isLessThanOrEqualTo(8_000);
    }

    @Test
    @DisplayName("색인이 짧으면 떼어 둔 자리를 항상 층이 쓴다")
    void alwaysLayerUsesReservedSpaceWhenIndexIsShort() {
        Memory body = memories.create(ADMIN, MemoryScope.GROUP, "본문", "가".repeat(7_000), true);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).contains(body.content(), "[" + indexed.id() + "] 색인 제목");
        assertThat(result.omittedItems()).isZero();
    }

    @Test
    @DisplayName("빠질 Memory 번호를 port 로 돌려준다")
    void returnsOmittedMemoryIdsThroughPort() {
        Memory tooLong = memories.create(ADMIN, MemoryScope.GROUP, "너무 긴 항목", "가".repeat(9_000), true);
        memories.create(ADMIN, MemoryScope.GROUP, "짧은 항목", "짧은 내용", true);

        assertThat(assembler.omittedFor(ADMIN))
                .containsExactly(tooLong.id())
                .isEqualTo(Set.copyOf(assembler.assembleForOwner(ADMIN).omittedMemoryIds()));
    }

    @Test
    @DisplayName("모두 실리면 빠질 Memory 번호가 비어 있다")
    void returnsNoOmittedMemoryIdsWhenEverythingFits() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 제목", "그룹 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", false);

        assertThat(assembler.omittedFor(ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("모든 항목이 상한을 넘으면 비우고 빠진 수만 남긴다")
    void emptiesAndKeepsOnlyOmittedCountWhenAllItemsExceedLimit() {
        memories.create(ADMIN, MemoryScope.GROUP, "첫째", "가".repeat(9_000), true);
        memories.create(ADMIN, MemoryScope.USER, "둘째", "나".repeat(9_000), true);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).isNull();
        assertThat(result.chars()).isZero();
        assertThat(result.omittedItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("상한 안에 다 들어가면 빠진 항목이 없다")
    void hasNoOmittedItemsWhenEverythingFitsLimit() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 제목", "그룹 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.omittedItems()).isZero();
        assertThat(result.omittedMemoryIds()).isEmpty();
    }

    @Test
    @DisplayName("항상 층 뒤에 색인 층을 id 오름차순으로 넣는다")
    void putsIndexLayerAfterAlwaysLayerInIdAscendingOrder() {
        Memory first = memories.create(ADMIN, MemoryScope.USER, "먼저 저장", LONG_BODY, false);
        Memory second = memories.create(ADMIN, MemoryScope.GROUP, "나중 저장", "본문", false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .contains("# 더 물어볼 수 있는 것", "[" + first.id() + "] 먼저 저장", "[" + second.id() + "] 나중 저장")
                .containsSubsequence("[" + first.id() + "] 먼저 저장", "[" + second.id() + "] 나중 저장");
    }

    @Test
    @DisplayName("커넥터 에이전트와 모르는 에이전트와 에이전트가 없는 실행은 Memory 를 하나도 받지 않는다")
    void connectorAndUnknownAgentsReceiveNoMemory() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 제목", "그룹 내용", true);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", false);
        Agent connector = Agent.of(
                "context-connector-" + System.nanoTime(),
                "연결",
                "context-connector-" + System.nanoTime(),
                "http://runtime.test/p/connector",
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.PRIVATE,
                ADMIN.id(),
                Instant.now());
        connector.markConnectorManaged();
        Long connectorId = agents.save(connector).id();

        assertThat(assembler.assemble(ADMIN, connectorId)).isEqualTo(AssembledContext.empty());
        assertThat(assembler.assemble(ADMIN, 9_999_999L)).isEqualTo(AssembledContext.empty());
        assertThat(assembler.assemble(ADMIN, null)).isEqualTo(AssembledContext.empty());
        assertThat(assembler.assemble(ADMIN, agentId).instructions()).contains("그룹 내용", "개인 제목");
    }

    @Test
    @DisplayName("에이전트가 받지 않는 collection 의 항목은 본문도 제목도 싣지 않는다")
    void leavesOutItemsOfCollectionsTheAgentDoesNotReceive() {
        memories.create(ADMIN, MemoryScope.USER, "기본 제목", "기본 내용", true);
        memories.create(
                ADMIN,
                MemoryScope.USER,
                "커리어 항상",
                "커리어 내용",
                "career",
                MemoryRetrieval.ALWAYS,
                MemorySensitivity.NORMAL);
        memories.create(
                ADMIN,
                MemoryScope.USER,
                "커리어 색인",
                "커리어 본문",
                "career",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.NORMAL);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).contains("기본 내용").doesNotContain("커리어 내용", "커리어 색인", "커리어 본문");
        assertThat(result.omittedMemoryIds()).isEmpty();
    }

    @Test
    @DisplayName("항상 층에 암호문인 줄이 있으면 그 줄만 건너뛰고 평문 항목은 싣는다")
    void skipsSealedRowsInAlwaysLayer() {
        // 그 에이전트는 core 만 받는다. collection 이 core 가 아니면 걸러지지 않아도 통과해 검사가 뜻을 잃는다
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, collection, entry_type, title, content, content_key_id,
                    retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES ('USER', 1, 'core', 'MEMORY', '직접 넣은 줄', 'v1.봉인-표식-5512', 'test-1', 'ALWAYS', TRUE,
                    'NORMAL', 1, 'ACCEPTED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """);
        memories.create(ADMIN, MemoryScope.USER, "개인 제목", "개인 내용", true);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).contains("개인 내용").doesNotContain("봉인-표식-5512");
    }

    @Test
    @DisplayName("사용자가 자기 목록을 볼 때의 조립은 collection 을 거르지 않는다")
    void ownerViewDoesNotFilterCollections() {
        memories.create(
                ADMIN,
                MemoryScope.USER,
                "커리어 색인",
                "커리어 본문",
                "career",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.NORMAL);

        assertThat(assembler.assembleForOwner(ADMIN).instructions()).contains("커리어 색인");
        assertThat(assembler.assembleForOwner(MEMBER)).isEqualTo(AssembledContext.empty());
    }

    @Test
    @DisplayName("충돌이 없으면 항목에서 옮긴 글이 지금 형식과 같고 지문도 그 글의 것이다")
    void rendersSameTextAndHashFromItemsWithoutConflict() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 회의", "주간 회의는 화요일 10시", true);
        memories.create(ADMIN, MemoryScope.USER, "아침 습관", "아침에는 차를 마신다", true);
        Memory firstIndexed = memories.create(ADMIN, MemoryScope.USER, "장보기 목록", "우유와 달걀", false);
        Memory secondIndexed = memories.create(ADMIN, MemoryScope.GROUP, "여행 계획", "가을에 바다", false);
        String expected = "# 우리 그룹이 함께 아는 것\n\n"
                + "- 주간 회의는 화요일 10시\n\n"
                + "# 지금 묻는 사람에 대해 아는 것\n\n"
                + "- 아침에는 차를 마신다\n\n"
                + FACTS_HEADER + "\n\n"
                + "- [" + firstIndexed.id() + "] 장보기 목록: 우유와 달걀\n\n"
                + "# 더 물어볼 수 있는 것\n\n"
                + "아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.\n\n"
                + "- [" + secondIndexed.id() + "] 여행 계획";

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).isEqualTo(expected);
        assertThat(result.chars()).isEqualTo(expected.length());
        assertThat(result.instructionsHash()).isEqualTo(Sha256.hex16(expected));
    }

    @Test
    @DisplayName("묶음은 글에 실은 순서대로 항상 층 항목과 개인 사실 항목과 제목만 실은 색인 항목을 갖는다")
    void bundlesItemsInRenderedOrder() {
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, "그룹 회의", "주간 회의는 화요일 10시", true);
        Memory personal = memories.create(ADMIN, MemoryScope.USER, "아침 습관", "아침에는 차를 마신다", true);
        Memory firstIndexed = memories.create(ADMIN, MemoryScope.USER, "장보기 목록", "우유와 달걀", false);
        Memory secondIndexed = memories.create(ADMIN, MemoryScope.GROUP, "여행 계획", "가을에 바다", false);

        List<ContextItem> items = assembler.assemble(ADMIN, agentId).bundle().items();

        assertThat(items)
                .extracting(ContextItem::source, ContextItem::ref, ContextItem::bodyMode)
                .containsExactly(
                        tuple(ContextSource.MEMORY_ALWAYS, "memory:" + group.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_ALWAYS, "memory:" + personal.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_FACTS, "memory:" + firstIndexed.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_INDEX, "memory:" + secondIndexed.id(), ContextBodyMode.TITLE_ONLY));
        assertThat(items)
                .extracting(ContextItem::title, ContextItem::body)
                .containsExactly(
                        tuple(null, "주간 회의는 화요일 10시"),
                        tuple(null, "아침에는 차를 마신다"),
                        tuple("장보기 목록", "우유와 달걀"),
                        tuple("여행 계획", null));
        assertThat(items)
                .extracting(ContextItem::scope, ContextItem::ownerUserId)
                .containsExactly(
                        tuple(MemoryScope.GROUP, null),
                        tuple(MemoryScope.USER, ADMIN.id()),
                        tuple(MemoryScope.USER, ADMIN.id()),
                        tuple(MemoryScope.GROUP, null));
        assertThat(items).allSatisfy(item -> {
            assertThat(item.trust()).isEqualTo(ContextTrust.USER_APPROVED);
            assertThat(item.freshness()).isEqualTo(ContextFreshness.FRESH);
            assertThat(item.sensitivity()).isEqualTo(MemorySensitivity.NORMAL);
            assertThat(item.asOf()).isNotNull();
            assertThat(item.conflictsWith()).isEmpty();
        });
    }

    @Test
    @DisplayName("상한을 넘어 뺀 항목은 묶음의 원래 자리에 빠졌다고 남는다")
    void keepsOmittedItemInBundleAtItsPlace() {
        Memory tooLong = memories.create(ADMIN, MemoryScope.GROUP, "너무 긴 항목", "가".repeat(9_000), true);
        Memory shortOne = memories.create(ADMIN, MemoryScope.GROUP, "짧은 항목", "짧은 내용", true);
        // 본문이 개인 사실 구역의 상한을 넘어 색인에만 오른다
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인만 하는 제목", LONG_BODY, false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.bundle().items())
                .extracting(ContextItem::ref, ContextItem::bodyMode)
                .containsExactly(
                        tuple("memory:" + tooLong.id(), ContextBodyMode.OMITTED),
                        tuple("memory:" + shortOne.id(), ContextBodyMode.INLINE),
                        tuple("memory:" + indexed.id(), ContextBodyMode.TITLE_ONLY));
        assertThat(result.bundle().items())
                .filteredOn(item -> item.bodyMode() == ContextBodyMode.OMITTED)
                .hasSize(result.omittedItems());
    }

    @Test
    @DisplayName("같은 이름의 개인 문서와 그룹 문서는 색인 줄 끝에 서로를 가리키고 충돌 상대로 서로를 적는다")
    void marksSameNamePersonalAndGroupDocumentsAsConflicting() {
        Long personal = insertDocument("USER", 1L, null, "home-rules", "집 관리 규칙");
        Long group = insertDocument("GROUP", null, 10L, "home-rules", "집 관리 규칙");
        Long other = insertDocument("USER", 1L, null, "garden-notes", "정원 메모");
        String expected = "# 더 물어볼 수 있는 것\n\n"
                + "아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.\n\n"
                + "- [" + personal + "] 집 관리 규칙 (같은 이름의 그룹 문서 [" + group + "] 가 있다)\n\n"
                + "- [" + group + "] 집 관리 규칙 (같은 이름의 개인 문서 [" + personal + "] 가 있다)\n\n"
                + "- [" + other + "] 정원 메모";

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).isEqualTo(expected);
        assertThat(result.bundle().items())
                .extracting(ContextItem::ref, ContextItem::conflictsWith)
                .containsExactly(
                        tuple("memory:" + personal, List.of("memory:" + group)),
                        tuple("memory:" + group, List.of("memory:" + personal)),
                        tuple("memory:" + other, List.of()));
        assertThat(result.omittedItems()).isZero();
    }

    @Test
    @DisplayName("조립 결과와 묶음과 항목의 문자열 표현에 제목과 본문이 없다")
    void keepsTitlesAndBodiesOutOfToString() {
        memories.create(ADMIN, MemoryScope.USER, "항상 제목", "평문-표식-7391", true);
        memories.create(ADMIN, MemoryScope.USER, "제목-표식-4820", "색인 본문", false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).contains("평문-표식-7391", "제목-표식-4820");
        assertThat(result.toString())
                .startsWith("AssembledContext[chars=" + result.chars())
                .doesNotContain("평문-표식-7391", "제목-표식-4820");
        assertThat(result.bundle().toString())
                .startsWith("ContextBundle[size=2")
                .doesNotContain("평문-표식-7391", "제목-표식-4820", "항상 제목", "색인 본문");
        assertThat(result.bundle().items())
                .allSatisfy(item -> assertThat(item.toString())
                        .isEqualTo("ContextItem[source=" + item.source() + ", ref=" + item.ref() + "]"));
    }

    @Test
    @DisplayName("짧은 개인 색인 항목은 개인 사실 구역에 본문까지 한 줄로 싣고 색인에서 뺀다")
    void placesShortPersonalItemInFactsSectionInsteadOfIndex() {
        Memory always = memories.create(ADMIN, MemoryScope.USER, "아침 습관", "아침에는 차를 마신다", true);
        Memory fact = memories.create(ADMIN, MemoryScope.USER, "딸 이름", "홍지수\n둘째는\r\n아직\r없다", false);
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "긴 기록", LONG_BODY, false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .containsSubsequence(
                        "- 아침에는 차를 마신다",
                        FACTS_HEADER,
                        "- [" + fact.id() + "] 딸 이름: 홍지수 둘째는 아직 없다",
                        INDEX_HEADER,
                        "- [" + indexed.id() + "] 긴 기록");
        assertThat(indexSection(result)).doesNotContain("[" + fact.id() + "]");
        assertThat(result.bundle().items())
                .extracting(ContextItem::source, ContextItem::ref, ContextItem::bodyMode)
                .containsExactly(
                        tuple(ContextSource.MEMORY_ALWAYS, "memory:" + always.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_FACTS, "memory:" + fact.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_INDEX, "memory:" + indexed.id(), ContextBodyMode.TITLE_ONLY));
        assertThat(result.omittedMemoryIds()).isEmpty();
    }

    @Test
    @DisplayName("그룹 항목과 201자 본문과 민감 항목과 문서는 개인 사실 구역에 없고 색인에 제목으로 있다")
    void leavesNonCandidatesInIndexByTitle() {
        Memory atLimit = memories.create(ADMIN, MemoryScope.USER, "상한 본문", "나".repeat(200), false);
        Memory group = memories.create(ADMIN, MemoryScope.GROUP, "그룹 사실", "그룹 본문", false);
        Memory overLimit = memories.create(ADMIN, MemoryScope.USER, "넘친 본문", "다".repeat(201), false);
        Memory sensitive = memories.create(
                ADMIN,
                MemoryScope.USER,
                "민감 사실",
                "민감 본문",
                Memory.DEFAULT_COLLECTION,
                MemoryRetrieval.SEARCH,
                MemorySensitivity.SENSITIVE);
        Long document = insertDocument("USER", ADMIN.id(), null, "short-note", "짧은 문서");

        // 민감 항목은 이 시험의 에이전트가 받지 않으므로 collection 과 민감도를 거르지 않는 주인 조립으로 본다
        AssembledContext result = assembler.assembleForOwner(ADMIN);

        assertThat(factsSection(result))
                .contains("- [" + atLimit.id() + "] 상한 본문: " + atLimit.content())
                .doesNotContain(
                        "[" + group.id() + "]",
                        "[" + overLimit.id() + "]",
                        "[" + sensitive.id() + "]",
                        "[" + document + "]");
        assertThat(indexSection(result))
                .contains(
                        "- [" + group.id() + "] 그룹 사실",
                        "- [" + overLimit.id() + "] 넘친 본문",
                        "- [" + sensitive.id() + "] 민감 사실",
                        "- [" + document + "] 짧은 문서")
                .doesNotContain("그룹 본문", "다".repeat(201), "민감 본문", "문서 본문");
        assertThat(result.bundle().items())
                .filteredOn(item -> item.source() == ContextSource.MEMORY_INDEX)
                .extracting(ContextItem::ref, ContextItem::bodyMode)
                .containsExactly(
                        tuple("memory:" + group.id(), ContextBodyMode.TITLE_ONLY),
                        tuple("memory:" + overLimit.id(), ContextBodyMode.TITLE_ONLY),
                        tuple("memory:" + sensitive.id(), ContextBodyMode.TITLE_ONLY),
                        tuple("memory:" + document, ContextBodyMode.TITLE_ONLY));
    }

    @Test
    @DisplayName("개인 사실 구역 예산을 넘으면 최근에 고친 것부터 담고 빠진 후보는 색인에 제목으로 남아 빠진 항목이 아니다")
    void choosesRecentFactsWithinBudgetAndLeavesRestInIndex() {
        Memory newest = memories.create(ADMIN, MemoryScope.USER, "가장 최근", "라".repeat(50), false);
        Memory longer = memories.create(ADMIN, MemoryScope.USER, "두 번째", "마".repeat(200), false);
        Memory third = memories.create(ADMIN, MemoryScope.USER, "세 번째", "바".repeat(50), false);
        Memory oldest = memories.create(ADMIN, MemoryScope.USER, "가장 오래", "사".repeat(50), false);
        // 번호가 작을수록 최근에 고친 것으로 둔다. 번호 순으로 고르면 다른 집합이 나온다
        Instant base = Instant.parse("2026-10-01T00:00:00Z");
        touch(newest, base);
        touch(longer, base.minus(Duration.ofDays(1)));
        touch(third, base.minus(Duration.ofDays(2)));
        touch(oldest, base.minus(Duration.ofDays(3)));
        // 가장 최근 것과 세 번째가 꼭 들어가는 예산이다. 두 번째는 넘쳐 건너뛰고 가장 오래된 것은 자리가 없다
        int budget = FACTS_HEADER.length()
                + 2
                + factLine(newest).length()
                + 2
                + factLine(third).length();

        AssembledContext result = assemblerWithFactsBudget(budget).assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .startsWith(
                        FACTS_HEADER + "\n\n" + factLine(newest) + "\n\n" + factLine(third) + "\n\n" + INDEX_HEADER);
        assertThat(result.bundle().items())
                .extracting(ContextItem::source, ContextItem::ref, ContextItem::bodyMode)
                .containsExactly(
                        tuple(ContextSource.MEMORY_FACTS, "memory:" + newest.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_FACTS, "memory:" + third.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_INDEX, "memory:" + longer.id(), ContextBodyMode.TITLE_ONLY),
                        tuple(ContextSource.MEMORY_INDEX, "memory:" + oldest.id(), ContextBodyMode.TITLE_ONLY));
        assertThat(result.omittedMemoryIds()).isEmpty();
        assertThat(result.omittedItems()).isZero();
    }

    @Test
    @DisplayName("항상 층이 색인 몫 밖의 자리를 거의 다 쓰면 개인 사실 구역이 비고 후보는 색인에 제목으로 남는다")
    void leavesFactsEmptyWhenAlwaysLayerUsesNearlyAllRoom() {
        Memory always = memories.create(ADMIN, MemoryScope.GROUP, "거의 상한", "가".repeat(7_850), true);
        Memory candidate = memories.create(ADMIN, MemoryScope.USER, "짧은 사실", "짧은 본문", false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .contains(always.content(), INDEX_HEADER, "- [" + candidate.id() + "] 짧은 사실")
                .doesNotContain(FACTS_HEADER, "짧은 본문");
        assertThat(result.bundle().items())
                .extracting(ContextItem::source, ContextItem::ref, ContextItem::bodyMode)
                .containsExactly(
                        tuple(ContextSource.MEMORY_ALWAYS, "memory:" + always.id(), ContextBodyMode.INLINE),
                        tuple(ContextSource.MEMORY_INDEX, "memory:" + candidate.id(), ContextBodyMode.TITLE_ONLY));
        assertThat(result.omittedMemoryIds()).isEmpty();
        assertThat(assembler.omittedFor(ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("고른 집합이 같으면 고르는 차례가 바뀌어도 글과 지문이 같다")
    void keepsSameTextAndHashWhenSameFactsAreChosen() {
        Memory first = memories.create(ADMIN, MemoryScope.USER, "딸 이름", "홍지수", false);
        memories.create(ADMIN, MemoryScope.USER, "음식 선호", "매운 음식을 못 먹는다", false);
        memories.create(ADMIN, MemoryScope.USER, "출근 방법", "지하철로 다닌다", false);

        AssembledContext before = assembler.assemble(ADMIN, agentId);
        AssembledContext again = assembler.assemble(ADMIN, agentId);
        // 번호가 가장 작은 항목을 가장 최근에 고친 것으로 바꿔 고르는 차례를 뒤집는다
        touch(first, Instant.now().plus(Duration.ofDays(1)));
        AssembledContext reordered = assembler.assemble(ADMIN, agentId);

        assertThat(before.instructions()).contains(FACTS_HEADER).doesNotContain(INDEX_HEADER);
        assertThat(again.instructionsHash()).isEqualTo(before.instructionsHash());
        assertThat(reordered.instructions()).isEqualTo(before.instructions());
        assertThat(reordered.instructionsHash()).isEqualTo(before.instructionsHash());
    }

    @Test
    @DisplayName("개인 사실 구역 예산이 0 이면 구역 없이 짧은 개인 항목도 색인에 제목으로 싣는다")
    void rendersPreviousTextWhenFactsBudgetIsZero() {
        memories.create(ADMIN, MemoryScope.GROUP, "그룹 회의", "주간 회의는 화요일 10시", true);
        memories.create(ADMIN, MemoryScope.USER, "아침 습관", "아침에는 차를 마신다", true);
        Memory firstIndexed = memories.create(ADMIN, MemoryScope.USER, "장보기 목록", "우유와 달걀", false);
        Memory secondIndexed = memories.create(ADMIN, MemoryScope.GROUP, "여행 계획", "가을에 바다", false);
        String expected = "# 우리 그룹이 함께 아는 것\n\n"
                + "- 주간 회의는 화요일 10시\n\n"
                + "# 지금 묻는 사람에 대해 아는 것\n\n"
                + "- 아침에는 차를 마신다\n\n"
                + "# 더 물어볼 수 있는 것\n\n"
                + "아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.\n\n"
                + "- [" + firstIndexed.id() + "] 장보기 목록\n\n"
                + "- [" + secondIndexed.id() + "] 여행 계획";

        AssembledContext result = assemblerWithFactsBudget(0).assemble(ADMIN, agentId);

        assertThat(result.instructions()).isEqualTo(expected);
        assertThat(result.instructionsHash()).isEqualTo(Sha256.hex16(expected));
        assertThat(result.bundle().items()).noneMatch(item -> item.source() == ContextSource.MEMORY_FACTS);
    }

    @Test
    @DisplayName("에이전트가 받지 않는 collection 의 짧은 개인 항목은 개인 사실 구역에도 없다")
    void leavesOutFactsOfCollectionsTheAgentDoesNotReceive() {
        Memory core = memories.create(ADMIN, MemoryScope.USER, "기본 사실", "기본 본문", false);
        Memory career = memories.create(
                ADMIN,
                MemoryScope.USER,
                "커리어 사실",
                "커리어 본문",
                "career",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.NORMAL);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions())
                .contains(FACTS_HEADER, "- [" + core.id() + "] 기본 사실: 기본 본문")
                .doesNotContain("커리어 사실", "커리어 본문");
        assertThat(result.bundle().items()).extracting(ContextItem::ref).doesNotContain("memory:" + career.id());
    }

    /** 개인 사실 구역 예산만 바꾼 조립기다. 나머지 설정은 운영 바인딩 값을 쓴다. */
    private ContextAssembler assemblerWithFactsBudget(int factsMaxChars) {
        return new ContextAssembler(
                memories,
                new ContextProperties(
                        properties.maxChars(),
                        properties.indexBudgetRatio(),
                        properties.resultStaleAfter(),
                        properties.memoryStaleAfter(),
                        properties.memoryCollectionStaleAfter(),
                        factsMaxChars,
                        properties.factsItemMaxChars()),
                clock);
    }

    private void touch(Memory memory, Instant updatedAt) {
        jdbc.update("UPDATE memory SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), memory.id());
    }

    private static String factLine(Memory memory) {
        return "- [" + memory.id() + "] " + memory.title() + ": " + memory.content();
    }

    /** 개인 사실 구역 머리부터 색인 머리 앞까지다. 구역이 없으면 빈 글이다. */
    private static String factsSection(AssembledContext result) {
        String text = result.instructions();
        int start = text.indexOf(FACTS_HEADER);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf(INDEX_HEADER, start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    /** 색인 머리부터 끝까지다. 색인이 없으면 빈 글이다. */
    private static String indexSection(AssembledContext result) {
        String text = result.instructions();
        int start = text.indexOf(INDEX_HEADER);
        return start < 0 ? "" : text.substring(start);
    }

    /** 문서를 저장소에 바로 넣는다. 그룹 문서는 서비스로 만들 수 없어 표에 직접 쓴다. */
    private Long insertDocument(String scope, Long ownerUserId, Long groupId, String documentKey, String title) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, group_id, collection, entry_type, document_key, title,
                    content, retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES (?, ?, ?, 'core', 'DOCUMENT', ?, ?, '문서 본문', 'SEARCH', FALSE, 'NORMAL', 1, 'ACCEPTED',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, scope, ownerUserId, groupId, documentKey, title);
        return jdbc.queryForObject(
                "SELECT id FROM memory WHERE scope = ? AND document_key = ?", Long.class, scope, documentKey);
    }

    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
    }
}
