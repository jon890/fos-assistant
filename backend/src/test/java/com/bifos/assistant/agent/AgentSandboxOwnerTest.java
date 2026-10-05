package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 셸과 파일 도구의 격리 실행 공간 주인 키를 에이전트의 주인으로 정하는지 본다(ADR-084). */
class AgentSandboxOwnerTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    @DisplayName("주인이 있으면 u 와 사용자 번호다")
    void ownedAgentUsesUserKey() {
        Agent agent = agentWith(42L, AgentVisibility.PRIVATE, 7L);

        assertThat(agent.sandboxOwner()).isEqualTo("u7");
    }

    @Test
    @DisplayName("주인이 없으면 a 와 에이전트 번호다")
    void ownerlessAgentUsesAgentKey() {
        Agent agent = agentWith(42L, AgentVisibility.GROUP, null);

        assertThat(agent.sandboxOwner()).isEqualTo("a42");
    }

    private static Agent agentWith(Long id, AgentVisibility visibility, Long ownerUserId) {
        Agent agent = Agent.of(
                "sandbox",
                "실행 공간",
                "sandbox-profile",
                "http://agent-runtime.test/p/sandbox-profile",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                ownerUserId,
                NOW);
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }
}
