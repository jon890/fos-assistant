package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 효력 있는 대체 연결이 가리키는 기억을 조립의 모든 층에서 빼고, 연결을 끊으면 다시 싣는지 확인한다(ADR-20261015 / memory-link). */
@BackendIntegrationTest
class ContextAssemblerSupersedeTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "user1@example.com", "user1", 10L, UserRole.ADMIN);
    private static final String AGENT_CODE = "context-supersede-test";

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

    private Long agentId;


    @BeforeEach
    void setUp() {
        links.deleteAll();
        repository.deleteAll();
        agentId = agents.findByCode(AGENT_CODE)
            .orElseGet(() -> agents.save(Agent.of(
                AGENT_CODE,
                "대체 조립 검사",
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
    @DisplayName("대체를 받아들이면 옛 항목이 개인 사실 구역과 색인 어디에도 없고 제안만으로는 바뀌지 않으며 끊으면 다시 실린다")
    void dropsSupersededFactUntilRevoked() {
        Memory old = memories.create(ADMIN, MemoryScope.USER, "사는 곳", "서울 마포구에 산다", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.USER, "이사한 곳", "부산 해운대구에 산다", false);
        assertThat(refsOf(assembler.assemble(ADMIN, agentId))).contains(ref(old), ref(fresh));

        MemoryLink link = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
        AssembledContext proposed = assembler.assemble(ADMIN, agentId);

        assertThat(refsOf(proposed)).as("제안은 주입을 바꾸지 않는다").contains(ref(old), ref(fresh));

        linkService.accept(ADMIN, link.id());
        AssembledContext accepted = assembler.assemble(ADMIN, agentId, "서울 마포구 어디 살았지?");

        assertThat(refsOf(accepted)).contains(ref(fresh)).doesNotContain(ref(old));
        assertThat(accepted.instructions()).doesNotContain("서울 마포구에 산다");

        linkService.revoke(ADMIN, link.id());

        assertThat(refsOf(assembler.assemble(ADMIN, agentId))).contains(ref(old), ref(fresh));
    }


    @Test
    @DisplayName("항상 층의 대체된 그룹 항목은 싣지 않는다")
    void dropsSupersededAlwaysItem() {
        Memory old = memories.create(ADMIN, MemoryScope.GROUP, "저녁 규칙", "저녁은 여섯 시에 먹는다", true);
        Memory fresh = memories.create(ADMIN, MemoryScope.GROUP, "새 저녁 규칙", "저녁은 일곱 시에 먹는다", true);
        MemoryLink link = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
        linkService.accept(ADMIN, link.id());

        AssembledContext result = assembler.assemble(ADMIN, agentId);

        assertThat(refsOf(result)).contains(ref(fresh)).doesNotContain(ref(old));
        assertThat(result.instructions()).doesNotContain("여섯 시");
    }


    @Test
    @DisplayName("질문 낱말이 대체된 항목의 본문에만 맞으면 질문과 관련된 기억 구역에 싣지 않는다")
    void dropsSupersededItemFromQuestionSection() {
        Memory old = memories.create(ADMIN, MemoryScope.GROUP, "아이 학원", "피아노 학원은 화요일 네 시에 간다", false);
        Memory fresh = memories.create(ADMIN, MemoryScope.GROUP, "아이 학원 변경", "학원 시간이 목요일로 바뀌었다", false);
        MemoryLink link = linkService.proposeSupersede(ADMIN, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
        linkService.accept(ADMIN, link.id());

        AssembledContext result = assembler.assemble(ADMIN, agentId, "피아노 화요일에 가지?");

        assertThat(refsOf(result)).doesNotContain(ref(old));
        assertThat(result.instructions()).doesNotContain("화요일 네 시");
    }


    private static List<String> refsOf(AssembledContext result) {
        return result.bundle().items().stream()
            .map(ContextItem::ref)
            .filter(r -> r.startsWith("memory:"))
            .toList();
    }


    private static String ref(Memory memory) {
        return "memory:" + memory.id();
    }
}
