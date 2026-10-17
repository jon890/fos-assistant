package com.bifos.assistant.context.application;

import com.bifos.assistant.context.application.dto.AssembledContext;
import com.bifos.assistant.context.application.dto.ContextBodyMode;
import com.bifos.assistant.context.application.dto.ContextItem;
import com.bifos.assistant.context.application.dto.ContextSelection;
import com.bifos.assistant.context.application.dto.ContextSource;
import com.bifos.assistant.context.application.dto.ContextTrust;
import com.bifos.assistant.context.config.ContextProperties;
import com.bifos.assistant.context.config.MemorySearchSectionProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionId;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TestContextAssemblers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 질문을 넘긴 조립이 질문과 관련된 기억 구역을 싣고 색인을 관련도 순으로 늘어놓는지 확인한다. */
@BackendIntegrationTest
class ContextAssemblerQuestionTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "user1@example.com", "user1", 10L, UserRole.ADMIN);
    private static final String AGENT_CODE = "context-question-test";
    private static final String SEARCH_HEADER = "# 질문과 관련된 기억";
    private static final String INDEX_HEADER = "# 더 물어볼 수 있는 것";
    private static final String HEALTH = "health";
    private static final String PEANUT_QUESTION = "땅콩 들어간 거 먹어도 돼?";

    @Autowired
    ContextAssembler assembler;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository repository;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContextProperties properties;

    @Autowired
    TestClock clock;

    private Long agentId;


    @BeforeEach
    void setUp() {
        repository.deleteAll();
        // 구역 예산이 줄 길이에 맞물려 있어 기억 번호의 자릿수가 결과를 가른다. 다른 테스트가 번호를 올려 둔 채로 오면
        // 3자리 번호의 줄이 예산에 안 들어가 순서에 따라 결과가 달라지므로, 번호를 처음부터 매기게 고정한다.
        jdbc.execute("ALTER TABLE memory ALTER COLUMN id RESTART WITH 1");
        agentId = agents.findByCode(AGENT_CODE)
            .orElseGet(() -> agents.save(Agent.of(
                AGENT_CODE,
                "질문 조립 검사",
                AGENT_CODE,
                "http://runtime.test/p/" + AGENT_CODE,
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                null)))
            .id();
        if (grants.findById(new AgentMemoryCollectionId(agentId, HEALTH)).isEmpty()) {
            grants.save(AgentMemoryCollection.of(agentId, HEALTH, true));
        }
    }


    @Test
    @DisplayName("본문에만 질문 낱말이 있는 항목을 질문과 관련된 기억 구역에 본문까지 싣고 색인에서 뺀다")
    void placesBodyMatchInSearchSectionAndRemovesItFromIndex() {
        Memory peanut = group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");
        Memory other = group("장보기", "우유와 계란을 산다");

        AssembledContext result = assembler.assemble(ADMIN, agentId, PEANUT_QUESTION);

        assertThat(section(result, SEARCH_HEADER))
            .contains("- [" + peanut.id() + "] 아이 간식 주의: " + peanut.content());
        assertThat(section(result, INDEX_HEADER))
            .contains("- [" + other.id() + "] 장보기")
            .doesNotContain("[" + peanut.id() + "]");
        ContextItem placed = itemOf(result, peanut, ContextSource.MEMORY_SEARCH).orElseThrow();
        assertThat(placed.bodyMode()).isEqualTo(ContextBodyMode.INLINE);
        assertThat(placed.trust()).isEqualTo(ContextTrust.USER_APPROVED);
        assertThat(placed.sensitivity()).isEqualTo(MemorySensitivity.NORMAL);
        assertThat(placed.selection().selector()).isEqualTo(ContextSelection.SEARCH_KEYWORD);
        assertThat(placed.selection().rank()).isEqualTo(1);
        assertThat(placed.selection().keywordScore()).isPositive();
        assertThat(placed.selection().score()).isEqualTo(placed.selection().keywordScore());
        assertThat(placed.selection().semanticScore()).isNull();
        assertThat(itemOf(result, other, ContextSource.MEMORY_INDEX).orElseThrow().selection())
            .isEqualTo(ContextSelection.ranked(ContextSelection.INDEX_BY_ID, 1));
    }


    @Test
    @DisplayName("어느 항목과도 낱말이 겹치지 않는 질문은 구역을 싣지 않고 질문 없는 조립과 같은 글을 낸다")
    void leavesTextUnchangedForUnrelatedQuestion() {
        group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");
        group("장보기", "우유와 계란을 산다");

        AssembledContext withQuestion = assembler.assemble(ADMIN, agentId, "주말 날씨 어때");
        AssembledContext withoutQuestion = assembler.assemble(ADMIN, agentId);

        assertThat(withQuestion.instructions()).doesNotContain(SEARCH_HEADER).isEqualTo(withoutQuestion.instructions());
        assertThat(withQuestion.bundle()).isEqualTo(withoutQuestion.bundle());
    }


    @Test
    @DisplayName("민감 항목과 암호화한 항목은 본문으로 구역에 오르지 못하고 제목이 맞으면 색인 앞쪽에 점수 근거와 함께 온다")
    void keepsSensitiveAndSealedItemsTitleOnly() {
        Memory plain = group("가족 행사", "다음 달 첫째 주 토요일에 할머니 댁에 간다");
        Memory sensitiveTitle = memories.create(
            ADMIN,
            MemoryScope.USER,
            "땅콩 들어간 간식 진료",
            "진료 기록 본문",
            HEALTH,
            MemoryRetrieval.SEARCH,
            MemorySensitivity.SENSITIVE);
        Memory sensitiveBody = memories.create(
            ADMIN,
            MemoryScope.USER,
            "병원 기록",
            "땅콩 들어간 음식은 먹으면 안 된다",
            HEALTH,
            MemoryRetrieval.SEARCH,
            MemorySensitivity.SENSITIVE);
        Long sealed = insertSealed("땅콩 들어간 과자 영수증");

        AssembledContext result = assembler.assemble(ADMIN, agentId, PEANUT_QUESTION);

        assertThat(result.instructions()).doesNotContain(SEARCH_HEADER);
        assertThat(result.bundle().items())
            .filteredOn(item -> item.source() == ContextSource.MEMORY_INDEX)
            .extracting(ContextItem::ref, item -> item.selection().selector())
            .containsExactly(
                tuple("memory:" + sensitiveTitle.id(), ContextSelection.INDEX_KEYWORD),
                tuple("memory:" + sealed, ContextSelection.INDEX_KEYWORD),
                tuple("memory:" + plain.id(), ContextSelection.INDEX_BY_ID),
                tuple("memory:" + sensitiveBody.id(), ContextSelection.INDEX_BY_ID));
        assertThat(result.bundle().items())
            .filteredOn(item -> item.source() == ContextSource.MEMORY_INDEX)
            .extracting(item -> item.selection().rank())
            .containsExactly(1, 2, 3, 4);
        assertThat(section(result, INDEX_HEADER)).doesNotContain("진료 기록 본문", "먹으면 안 된다");
    }


    @Test
    @DisplayName("항목당 글자 수를 넘는 본문은 건너뛰고 다음 항목을 싣고 건수 상한을 지킨다")
    void skipsLongBodiesAndKeepsItemLimit() {
        Memory longBody = group("땅콩 들어간 간식 목록", "땅콩 들어간 과자 이름을 적는다. " + "가".repeat(80));
        Memory first = group("땅콩 들어간 빵", "땅콩 들어간 빵은 먹지 않는다");
        Memory second = group("땅콩 들어간 소스", "땅콩 들어간 소스는 피한다");
        ContextAssembler small = TestContextAssemblers.of(
            memories, properties, clock, new MemorySearchSectionProperties(1, 50, 1_500));

        AssembledContext result = small.assemble(ADMIN, agentId, PEANUT_QUESTION);

        List<ContextItem> placed = result.bundle().items().stream()
            .filter(item -> item.source() == ContextSource.MEMORY_SEARCH)
            .toList();
        assertThat(placed).singleElement().extracting(ContextItem::ref).isIn("memory:" + first.id(), "memory:" + second.id());
        assertThat(itemOf(result, longBody, ContextSource.MEMORY_INDEX).orElseThrow().selection().selector())
            .isEqualTo(ContextSelection.INDEX_KEYWORD);
        assertThat(section(result, SEARCH_HEADER)).doesNotContain("가".repeat(80));
    }


    @Test
    @DisplayName("다른 범위에 같은 이름의 짝이 있는 문서는 질문에 맞아도 구역에 싣지 않고 색인에 충돌 표시와 함께 둔다")
    void keepsSameNameDocumentsInIndexWithConflictMarks() {
        Long personal = insertDocument("USER", ADMIN.id(), null, "peanut-note", "땅콩 들어간 음식 메모");
        Long shared = insertDocument("GROUP", null, ADMIN.groupId(), "peanut-note", "땅콩 들어간 음식 메모");

        AssembledContext result = assembler.assemble(ADMIN, agentId, PEANUT_QUESTION);

        assertThat(result.instructions()).doesNotContain(SEARCH_HEADER);
        assertThat(section(result, INDEX_HEADER))
            .contains(
                "- [" + personal + "] 땅콩 들어간 음식 메모 (같은 이름의 그룹 문서 [" + shared + "] 가 있다)",
                "- [" + shared + "] 땅콩 들어간 음식 메모 (같은 이름의 개인 문서 [" + personal + "] 가 있다)");
        assertThat(result.bundle().items())
            .filteredOn(item -> item.source() == ContextSource.MEMORY_INDEX)
            .extracting(item -> item.selection().selector())
            .containsOnly(ContextSelection.INDEX_KEYWORD);
    }


    @Test
    @DisplayName("구역 글자 예산을 넘는 항목은 건너뛰어 색인 앞쪽에 둔다")
    void skipsItemsOverSectionBudget() {
        Memory peanut = group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");
        ContextAssembler tight = TestContextAssemblers.of(
            memories, properties, clock, new MemorySearchSectionProperties(3, 600, MemorySearchSection.HEADER.length() + 10));

        AssembledContext result = tight.assemble(ADMIN, agentId, PEANUT_QUESTION);

        assertThat(result.instructions()).doesNotContain(SEARCH_HEADER);
        assertThat(itemOf(result, peanut, ContextSource.MEMORY_INDEX).orElseThrow().selection().selector())
            .isEqualTo(ContextSelection.INDEX_KEYWORD);
    }


    @Test
    @DisplayName("앞 층이 자리를 다 써 구역 줄이 들어가지 않으면 그 항목은 색인에 남고 빠진 항목으로 세지 않는다")
    void leavesItemInIndexWhenNoRoomRemains() {
        memories.create(ADMIN, MemoryScope.GROUP, "늘 싣는 규칙", "가".repeat(150), true);
        Memory peanut = group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");
        ContextAssembler small = TestContextAssemblers.of(
            memories, new ContextProperties(250, 4, null, null, Map.of(), 0, 200), clock);

        AssembledContext result = small.assemble(ADMIN, agentId, PEANUT_QUESTION);

        assertThat(result.instructions()).doesNotContain(SEARCH_HEADER);
        assertThat(itemOf(result, peanut, ContextSource.MEMORY_SEARCH)).isEmpty();
        assertThat(itemOf(result, peanut, ContextSource.MEMORY_INDEX)).isPresent();
        assertThat(result.omittedMemoryIds()).doesNotContain(peanut.id());
    }


    @Test
    @DisplayName("질문이 없거나 공백뿐인 조립은 지금까지와 같은 지시문 지문을 낸다")
    void keepsInstructionsHashWithoutQuestion() {
        group("아이 간식 주의", "둘째는 땅콩 알레르기라 땅콩 들어간 음식은 먹으면 안 된다");
        memories.create(ADMIN, MemoryScope.USER, "짧은 사실", "커피는 연하게 마신다", false);

        String withoutQuestion = assembler.assemble(ADMIN, agentId).instructionsHash();

        assertThat(assembler.assemble(ADMIN, agentId, null).instructionsHash()).isEqualTo(withoutQuestion);
        assertThat(assembler.assemble(ADMIN, agentId, "   ").instructionsHash()).isEqualTo(withoutQuestion);
        assertThat(assembler.assemble(ADMIN, agentId, PEANUT_QUESTION).instructionsHash()).isNotEqualTo(withoutQuestion);
    }


    /** 그룹 항목은 개인 사실 구역의 후보가 아니어서 질문 구역만 본다. */
    private Memory group(String title, String content) {
        return memories.create(ADMIN, MemoryScope.GROUP, title, content, false);
    }


    /** 문서를 저장소에 바로 넣는다. 그룹 문서는 서비스로 만들 수 없어 테이블에 직접 쓴다. */
    private Long insertDocument(String scope, Long ownerUserId, Long groupId, String documentKey, String title) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, group_id, collection, entry_type, document_key, title,
                    content, retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES (?, ?, ?, 'core', 'DOCUMENT', ?, ?, '땅콩 들어간 음식은 먹지 않는다', 'SEARCH', FALSE, 'NORMAL', 1, 'ACCEPTED',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, scope, ownerUserId, groupId, documentKey, title);
        return jdbc.queryForObject(
            "SELECT id FROM memory WHERE scope = ? AND document_key = ?", Long.class, scope, documentKey);
    }


    /** 본문이 암호문이라고 표시한 일반 민감도 줄이다. 데이터가 어긋난 경우를 흉내 낸다. */
    private Long insertSealed(String title) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, collection, entry_type, title, content, content_key_id,
                    retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES ('USER', 1, 'core', 'MEMORY', ?, 'v1.땅콩-들어간-봉인', 'test-1', 'SEARCH', FALSE,
                    'NORMAL', 1, 'ACCEPTED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, title);
        return jdbc.queryForObject("SELECT MAX(id) FROM memory", Long.class);
    }


    private static Optional<ContextItem> itemOf(AssembledContext result, Memory memory, ContextSource source) {
        return result.bundle().items().stream()
            .filter(item -> item.source() == source && item.ref().equals("memory:" + memory.id()))
            .findFirst();
    }


    /** 머리 줄부터 다음 머리 줄 앞까지다. 그 머리 줄이 없으면 빈 글이다. */
    private static String section(AssembledContext result, String header) {
        String text = result.instructions() == null ? "" : result.instructions();
        int start = text.indexOf(header);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("\n# ", start + header.length());
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}
