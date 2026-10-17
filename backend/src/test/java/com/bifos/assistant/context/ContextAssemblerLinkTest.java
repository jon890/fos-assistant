package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentMemoryCollectionId;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TestContextAssemblers;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실린 기억과 RELATED 로 이어진 한 단계 이웃을 연결된 기억 구역에 싣는지 확인한다(ADR-20261015 / memory-link). */
@BackendIntegrationTest
class ContextAssemblerLinkTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "user1@example.com", "user1", 10L, UserRole.ADMIN);
    private static final String AGENT_CODE = "context-link-test";
    private static final String LINK_HEADER = "# 연결된 기억";
    private static final String HEALTH = "health";

    @Autowired
    ContextAssembler assembler;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryLinkRepository links;

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
        links.deleteAll();
        repository.deleteAll();
        agentId = agents.findByCode(AGENT_CODE)
            .orElseGet(() -> agents.save(Agent.of(
                AGENT_CODE,
                "연결 조립 검사",
                AGENT_CODE,
                "http://runtime.test/p/" + AGENT_CODE,
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                null)))
            .id();
    }


    @Test
    @DisplayName("개인 사실 구역에 실린 항목의 이웃은 본문으로 실리고 두 단계 떨어진 항목은 색인에 남는다")
    void placesOneHopNeighborOfFact() {
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Memory hotel = memories.create(ADMIN, MemoryScope.USER, "제주 숙소", "숙소는 " + "바닷가 ".repeat(60), false);
        Memory dinner = memories.create(ADMIN, MemoryScope.USER, "제주 저녁", "저녁은 " + "흑돼지 ".repeat(60), false);
        relate(trip, hotel, 0.9);
        relate(hotel, dinner, 0.9);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(itemOf(result, trip, ContextSource.MEMORY_FACTS)).isPresent();
        ContextItem neighbor = itemOf(result, hotel, ContextSource.MEMORY_LINK).orElseThrow();
        assertThat(neighbor.bodyMode()).isEqualTo(ContextBodyMode.INLINE);
        assertThat(neighbor.selection().selector()).isEqualTo(ContextSelection.LINK_RELATED);
        assertThat(neighbor.selection().rank()).isEqualTo(1);
        assertThat(neighbor.selection().score()).isEqualTo(0.9);
        assertThat(result.instructions()).contains(LINK_HEADER).contains("바닷가");
        assertThat(itemOf(result, hotel, ContextSource.MEMORY_INDEX)).as("실린 이웃은 색인에서 빠진다").isEmpty();
        assertThat(itemOf(result, dinner, ContextSource.MEMORY_INDEX)).as("두 단계 떨어진 항목").isPresent();
        assertThat(itemOf(result, dinner, ContextSource.MEMORY_LINK)).isEmpty();
    }


    @Test
    @DisplayName("질문 구역에 실린 그룹 항목의 이웃도 싣고 사람이 맺은 연결을 코드가 맺은 연결보다 먼저 싣는다")
    void placesNeighborsOfSearchSectionInOrder() {
        Memory academy = group("피아노 학원", "피아노 학원은 화요일 네 시에 간다");
        Memory teacher = group("선생님 연락처", "선생님 연락은 " + "문자로 ".repeat(50));
        Memory book = group("교재 목록", "교재는 " + "체르니 ".repeat(50));
        relate(academy, book, 0.95);
        links.save(MemoryLink.related(academy.id(), teacher.id(), "같은 학원", MemoryLinkOrigin.HUMAN, null, null));

        AssembledContext result = assembler.assemble(ADMIN, agentId, "피아노 학원 언제 가?");

        assertThat(itemOf(result, academy, ContextSource.MEMORY_SEARCH)).isPresent();
        assertThat(itemOf(result, teacher, ContextSource.MEMORY_LINK).orElseThrow().selection().rank()).isEqualTo(1);
        assertThat(itemOf(result, book, ContextSource.MEMORY_LINK).orElseThrow().selection().rank()).isEqualTo(2);
    }


    @Test
    @DisplayName("본문이 긴 이웃과 대체된 이웃과 끊은 연결의 이웃은 본문으로 싣지 않는다")
    void skipsLongSupersededAndRevokedNeighbors() {
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Memory longNote = memories.create(ADMIN, MemoryScope.USER, "긴 메모", "가".repeat(700), false);
        Memory replaced = memories.create(ADMIN, MemoryScope.USER, "옛 숙소", "숙소는 " + "산속 ".repeat(60), false);
        Memory replacing = memories.create(ADMIN, MemoryScope.USER, "새 숙소", "숙소는 " + "바닷가 ".repeat(60), false);
        Memory revoked = memories.create(ADMIN, MemoryScope.USER, "취소한 일정", "일정은 " + "취소 ".repeat(60), false);
        relate(trip, longNote, 0.9);
        relate(trip, replaced, 0.9);
        MemoryLink revokedLink = relate(trip, revoked, 0.9);
        MemoryLink supersede = linkService.proposeSupersede(ADMIN, replacing.id(), replaced.id(), MemoryLinkOrigin.AGENT, null);
        linkService.accept(ADMIN, supersede.id());
        linkService.revoke(ADMIN, revokedLink.id());

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.bundle().items())
            .filteredOn(item -> item.source() == ContextSource.MEMORY_LINK)
            .isEmpty();
        assertThat(result.instructions()).doesNotContain(LINK_HEADER);
        assertThat(itemOf(result, longNote, ContextSource.MEMORY_INDEX)).isPresent();
        assertThat(itemOf(result, replaced, ContextSource.MEMORY_INDEX)).as("대체된 항목은 어디에도 없다").isEmpty();
    }


    @Test
    @DisplayName("다른 범위에 같은 이름의 짝이 있는 문서는 이웃이어도 싣지 않고 두 문서의 충돌 표시를 색인에 남긴다")
    void keepsSameNameDocumentsInIndex() {
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Long personal = insertDocument("USER", ADMIN.id(), null, "jeju-note", "제주 메모");
        Long shared = insertDocument("GROUP", null, ADMIN.groupId(), "jeju-note", "제주 메모");
        links.save(MemoryLink.related(trip.id(), personal, null, MemoryLinkOrigin.HUMAN, null, null));

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.bundle().items()).noneMatch(item -> item.source() == ContextSource.MEMORY_LINK);
        assertThat(result.instructions())
            .contains("- [" + personal + "] 제주 메모 (같은 이름의 그룹 문서 [" + shared + "] 가 있다)")
            .contains("- [" + shared + "] 제주 메모 (같은 이름의 개인 문서 [" + personal + "] 가 있다)");
    }


    /** 문서를 저장소에 바로 넣는다. 그룹 문서는 서비스로 만들 수 없어 테이블에 직접 쓴다. */
    private Long insertDocument(String scope, Long ownerUserId, Long groupId, String documentKey, String title) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, group_id, collection, entry_type, document_key, title,
                    content, retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES (?, ?, ?, 'core', 'DOCUMENT', ?, ?, '숙소는 바닷가 앞이다', 'SEARCH', FALSE, 'NORMAL', 1, 'ACCEPTED',
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, scope, ownerUserId, groupId, documentKey, title);
        return jdbc.queryForObject(
            "SELECT id FROM memory WHERE scope = ? AND document_key = ?", Long.class, scope, documentKey);
    }


    @Test
    @DisplayName("이웃은 질문 구역 설정의 건수와 글자 예산을 이 구역의 몫으로 따로 써 넘치는 이웃은 색인에 남긴다")
    void keepsLinkSectionWithinItsOwnBudget() {
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Memory hotel = memories.create(ADMIN, MemoryScope.USER, "제주 숙소", "숙소는 " + "바닷가 ".repeat(60), false);
        Memory dinner = memories.create(ADMIN, MemoryScope.USER, "제주 저녁", "저녁은 " + "흑돼지 ".repeat(60), false);
        relate(trip, hotel, 0.95);
        relate(trip, dinner, 0.9);
        ContextAssembler oneItem = TestContextAssemblers.of(
            memories, properties, clock, new MemorySearchSectionProperties(1, 600, 1_500));
        ContextAssembler tightChars = TestContextAssemblers.of(
            memories, properties, clock, new MemorySearchSectionProperties(3, 600, MemoryLinkSection.HEADER.length() + 10));
        ContextAssembler off = TestContextAssemblers.of(memories, properties, clock, new MemorySearchSectionProperties(0, 600, 1_500));

        AssembledContext one = oneItem.assemble(ADMIN, agentId);
        AssembledContext tight = tightChars.assemble(ADMIN, agentId);

        assertThat(itemOf(one, hotel, ContextSource.MEMORY_LINK)).isPresent();
        assertThat(itemOf(one, dinner, ContextSource.MEMORY_INDEX)).isPresent();
        assertThat(tight.bundle().items()).noneMatch(item -> item.source() == ContextSource.MEMORY_LINK);
        assertThat(itemOf(tight, hotel, ContextSource.MEMORY_INDEX)).isPresent();
        assertThat(off.assemble(ADMIN, agentId).bundle().items()).noneMatch(item -> item.source() == ContextSource.MEMORY_LINK);
    }


    @Test
    @DisplayName("에이전트가 받지 않는 collection 과 민감, 암호화한 이웃은 본문으로 싣지 않고 받지 않는 이웃은 색인에도 없다")
    void keepsNeighborsInsideAgentBoundary() {
        if (grants.findById(new AgentMemoryCollectionId(agentId, HEALTH)).isEmpty()) {
            grants.save(AgentMemoryCollection.of(agentId, HEALTH, true));
        }
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Memory career = memories.create(
            ADMIN, MemoryScope.USER, "출장", "제주 출장 일정", "career", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        Memory sensitive = memories.create(
            ADMIN, MemoryScope.USER, "멀미약", "제주 가는 배에서 먹는 약", HEALTH, MemoryRetrieval.SEARCH, MemorySensitivity.SENSITIVE);
        Long sealed = insertSealed("봉인한 숙소 메모");
        relate(trip, career, 0.9);
        relate(trip, sensitive, 0.9);
        links.save(MemoryLink.related(trip.id(), sealed, null, MemoryLinkOrigin.AUTO, 0.9, null));

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.bundle().items()).noneMatch(item -> item.source() == ContextSource.MEMORY_LINK);
        assertThat(result.bundle().items()).noneMatch(item -> item.ref().equals("memory:" + career.id()));
        assertThat(itemOf(result, sensitive, ContextSource.MEMORY_INDEX)).isPresent();
        assertThat(result.bundle().items())
            .anyMatch(item -> item.source() == ContextSource.MEMORY_INDEX && item.ref().equals("memory:" + sealed));
        assertThat(result.instructions()).doesNotContain("제주 출장 일정", "배에서 먹는 약", "v1.봉인");
    }


    @Test
    @DisplayName("사용자 목록 경로의 조립도 같은 규칙으로 이웃을 싣는다")
    void placesNeighborsForOwnerAssembly() {
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Memory hotel = memories.create(ADMIN, MemoryScope.USER, "제주 숙소", "숙소는 " + "바닷가 ".repeat(60), false);
        relate(trip, hotel, 0.9);

        AssembledContext result = assembler.assembleForOwner(ADMIN);

        assertThat(itemOf(result, hotel, ContextSource.MEMORY_LINK)).isPresent();
    }


    @Test
    @DisplayName("끊은 연결만 있으면 조립한 글과 지문이 연결이 없을 때와 같다")
    void keepsSameTextWithOnlyRevokedLinks() {
        Memory trip = memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        Memory hotel = memories.create(ADMIN, MemoryScope.USER, "제주 숙소", "숙소는 " + "바닷가 ".repeat(60), false);
        AssembledContext before = assembler.assemble(ADMIN, agentId);
        MemoryLink link = relate(trip, hotel, 0.9);
        linkService.revoke(ADMIN, link.id());

        AssembledContext after = assembler.assemble(ADMIN, agentId);

        assertThat(after.instructions()).isEqualTo(before.instructions());
        assertThat(after.bundle()).isEqualTo(before.bundle());
    }


    /** 본문이 암호문이라고 표시한 일반 민감도 줄이다. 데이터가 어긋난 경우를 흉내 낸다. */
    private Long insertSealed(String title) {
        jdbc.update("""
                INSERT INTO memory (scope, owner_user_id, collection, entry_type, title, content, content_key_id,
                    retrieval, always_inject, sensitivity, revision, status, created_at, updated_at)
                VALUES ('USER', 1, 'core', 'MEMORY', ?, 'v1.봉인-본문', 'test-1', 'SEARCH', FALSE,
                    'NORMAL', 1, 'ACCEPTED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, title);
        return jdbc.queryForObject("SELECT MAX(id) FROM memory", Long.class);
    }


    @Test
    @DisplayName("연결이 없으면 지시문이 연결을 두기 전과 같다")
    void keepsTextWithoutLinks() {
        memories.create(ADMIN, MemoryScope.USER, "여름 여행", "8월에 제주에 간다", false);
        memories.create(ADMIN, MemoryScope.USER, "제주 숙소", "숙소는 " + "바닷가 ".repeat(60), false);

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(result.instructions()).doesNotContain(LINK_HEADER);
        assertThat(result.bundle().items()).noneMatch(item -> item.source() == ContextSource.MEMORY_LINK);
    }


    private MemoryLink relate(Memory a, Memory b, double score) {
        return links.save(MemoryLink.related(a.id(), b.id(), null, MemoryLinkOrigin.AUTO, score, null));
    }


    private Memory group(String title, String content) {
        return memories.create(ADMIN, MemoryScope.GROUP, title, content, false);
    }


    private static Optional<ContextItem> itemOf(AssembledContext result, Memory memory, ContextSource source) {
        return result.bundle().items().stream()
            .filter(item -> item.source() == source && item.ref().equals("memory:" + memory.id()))
            .findFirst();
    }
}
