package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.agent.application.AgentMemoryGrants;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 에이전트가 받는 Memory collection 과 새 에이전트의 기본 부여를 확인한다(ADR-053). */
@SpringBootTest
@ActiveProfiles("test")
class AgentMemoryCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Autowired
    AgentMemoryCollectionService service;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    AgentRepository agents;

    @Test
    @DisplayName("새로 저장한 에이전트는 core 를 민감 항목 없이 받고 다시 저장해도 줄이 늘지 않는다")
    void newAgentsReceiveCoreOnceWithoutSensitiveItems() {
        Agent agent = agents.save(agent());

        AgentMemoryGrants granted = service.grantsOf(agent.id());
        assertThat(granted.collections()).containsExactly("core");
        assertThat(granted.sensitiveCollections()).isEmpty();

        agent.assignFlow(null);
        agents.save(agent);
        agents.saveAndFlush(agents.findById(agent.id()).orElseThrow());
        assertThat(grants.findByIdAgentId(agent.id())).hasSize(1);
    }

    @Test
    @DisplayName("이미 저장한 에이전트를 다시 저장해도 관리자가 바꾼 허용을 되돌리지 않는다")
    void savingAnExistingAgentAgainDoesNotResetItsGrants() {
        Agent agent = agents.save(agent());
        // 관리자가 core 의 민감 허용을 켰다. 사건이 다시 나가면 이 줄이 거짓으로 덮인다.
        grants.save(AgentMemoryCollection.of(agent.id(), "core", true, NOW));
        agent.assignFlow(null);
        agents.save(agent);
        agents.saveAndFlush(agents.findById(agent.id()).orElseThrow());
        assertThat(service.grantsOf(agent.id()).sensitiveCollections()).containsExactly("core");

        // 관리자가 core 를 뺐다. 사건이 다시 나가면 이 줄이 되살아난다.
        grants.deleteAll(grants.findByIdAgentId(agent.id()));
        agents.save(agents.findById(agent.id()).orElseThrow());
        assertThat(grants.findByIdAgentId(agent.id())).isEmpty();
    }

    @Test
    @DisplayName("saveAndFlush 로 저장한 에이전트도 core 를 받는다")
    void agentsSavedWithFlushAlsoReceiveCore() {
        Agent agent = agents.saveAndFlush(agent());

        assertThat(service.grantsOf(agent.id()).collections()).containsExactly("core");
    }

    @Test
    @DisplayName("관리자가 넓힌 collection 과 민감 허용을 그대로 낸다")
    void returnsWidenedCollectionsAndSensitiveAllowance() {
        Agent agent = agents.save(agent());
        grants.save(AgentMemoryCollection.of(agent.id(), "career", false, NOW));
        grants.save(AgentMemoryCollection.of(agent.id(), "identity", true, NOW));

        AgentMemoryGrants granted = service.grantsOf(agent.id());

        assertThat(granted.collections()).containsExactlyInAnyOrder("core", "career", "identity");
        assertThat(granted.sensitiveCollections()).containsExactly("identity");
    }

    @Test
    @DisplayName("커넥터 에이전트는 저장할 때 아무것도 받지 않고 줄이 있어도 받지 않는다")
    void connectorAgentsReceiveNothingEvenWithRows() {
        Agent connector = agent();
        connector.markConnectorManaged();
        Agent saved = agents.save(connector);

        assertThat(grants.findByIdAgentId(saved.id())).isEmpty();
        assertThat(service.grantsOf(saved.id()).collections()).isEmpty();

        grants.save(AgentMemoryCollection.of(saved.id(), "core", true, NOW));
        AgentMemoryGrants granted = service.grantsOf(saved.id());
        assertThat(granted.collections()).isEmpty();
        assertThat(granted.sensitiveCollections()).isEmpty();
    }

    @Test
    @DisplayName("없는 에이전트와 에이전트가 없는 실행은 아무것도 받지 않는다")
    void unknownAgentsReceiveNothing() {
        assertThat(service.grantsOf(9_999_999L).collections()).isEmpty();
        assertThat(service.grantsOf(null).collections()).isEmpty();
    }

    @Test
    @DisplayName("줄을 모두 뺀 에이전트는 core 도 받지 않는다")
    void agentsWithEveryRowRemovedReceiveNothing() {
        Agent agent = agents.save(agent());
        grants.deleteAll(grants.findByIdAgentId(agent.id()));

        assertThat(service.grantsOf(agent.id()).collections()).isEmpty();
    }

    private static Agent agent() {
        String code = "memory-grant-" + UUID.randomUUID().toString().substring(0, 12);
        return Agent.of(
                code,
                "collection 검사",
                code,
                "http://runtime.test/p/" + code,
                CostMode.API,
                CredentialScope.DEDICATED,
                AgentVisibility.GROUP,
                null, Instant.now());
    }
}
