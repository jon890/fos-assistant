package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentModelSync;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import org.junit.jupiter.api.Test;

class AgentModelSyncTest {
    private final HermesModelClient hermes = mock(HermesModelClient.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentModelSync sync = new AgentModelSync(hermes, agents);

    @Test
    void 모델이_바뀌면_모델과_확인_시각을_갱신한다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readModel(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn("gpt-5.6-sol");

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.read()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(agent.model()).isEqualTo("gpt-5.6-sol");
        assertThat(agent.modelSyncedAt()).isNotNull();
        verify(agents).save(agent);
    }

    @Test
    void 같은_모델도_확인_시각은_갱신한다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readModel(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn("gpt-5.5");

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.changed()).isFalse();
        assertThat(agent.modelSyncedAt()).isNotNull();
        verify(agents).save(agent);
    }

    @Test
    void 모델을_읽지_못하면_기존_값을_유지한다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readModel(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(null);

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.read()).isFalse();
        assertThat(agent.model()).isEqualTo("gpt-5.5");
        assertThat(agent.modelSyncedAt()).isNull();
    }

    private static Agent agent(String model) {
        return Agent.of("dad", "Dad", "dad", "http://hermes/p/dad", "openai-codex", model,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, 1L);
    }
}
