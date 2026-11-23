package com.bifos.assistant.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentRuntimeTargetTest {

    private final Agent agent = Agent.of(
        "target-agent",
        "대상 비서",
        "current-profile",
        "http://runtime.test/p/current-profile",
        CostMode.SUBSCRIPTION,
        CredentialScope.SHARED_HOUSEHOLD,
        AgentVisibility.PRIVATE,
        1L);


    @Test
    @DisplayName("새 실행의 대상은 에이전트의 지금 주소와 profile 이다")
    void targetsCurrentProfile() {
        assertThat(agent.runtimeTarget()).isEqualTo(
            new RuntimeTarget(RuntimeKind.HERMES, "http://runtime.test/p/current-profile", "current-profile"));
    }


    @Test
    @DisplayName("이미 보낸 실행의 대상은 저장된 격리 단위다")
    void targetsStoredUnit() {
        assertThat(agent.runtimeTargetOf("stored-profile")).isEqualTo(
            new RuntimeTarget(RuntimeKind.HERMES, "http://runtime.test/p/current-profile", "stored-profile"));
    }
}
