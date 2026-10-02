package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 실행 instructions 에 들어갈 Memory 층과 권한 경계를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class ContextAssemblerTest {

    private static final CurrentUser ADMIN = user(1L, 10L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = user(2L, 10L, UserRole.MEMBER);
    private static final String AGENT_CODE = "context-assembler-test";

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
                        null)))
                .id();
    }

    @Test
    @DisplayName("Memory 없이도 GFM 표 지침과 길이와 지문을 보낸다")
    void addsResponseInstructionsWithoutMemory() {
        AssembledContext result = assembler.withResponseInstructions(AssembledContext.empty());

        assertThat(result.instructions()).contains("GFM", "| --- | --- |");
        assertThat(result.chars()).isEqualTo(result.instructions().length());
        assertThat(result.instructionsHash()).isNotNull();
    }

    @Test
    @DisplayName("공통 표 지침은 Memory 예산 밖에 두고 누락 항목을 보존한다")
    void keepsMemoryBudgetAndOmissionsIndependent() {
        String body = "가".repeat(8_000);
        AssembledContext memory = new AssembledContext(body, body.length(), List.of(3L));

        AssembledContext result = assembler.withResponseInstructions(memory);

        assertThat(result.instructions()).startsWith("# 답변 형식").endsWith(body);
        assertThat(result.chars()).isEqualTo(result.instructions().length()).isGreaterThan(8_000);
        assertThat(result.omittedMemoryIds()).containsExactly(3L);
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
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인만 하는 제목", "색인 본문", false);

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
        Memory indexed = memories.create(ADMIN, MemoryScope.USER, "색인 제목", "색인 본문", false);

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
        Memory first = memories.create(ADMIN, MemoryScope.USER, "먼저 저장", "본문", false);
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
                ADMIN.id());
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

    private static CurrentUser user(Long id, Long groupId, UserRole role) {
        return new CurrentUser(id, "user" + id + "@example.com", "user" + id, groupId, role);
    }
}
