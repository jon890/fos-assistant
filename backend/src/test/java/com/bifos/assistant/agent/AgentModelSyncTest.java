package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.AgentModelSync;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.dto.HermesModelOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentModelSyncTest {
    private final HermesModelClient hermes = mock(HermesModelClient.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentModelSelector models = mock(AgentModelSelector.class);
    private final AgentModelSync sync = new AgentModelSync(hermes, agents, models);

    @Test
    void 모델이_바뀌면_1순위와_확인_시각을_갱신한다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readOptions(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(new HermesModelOptions("gpt-5.6-sol", "openai-codex"));
        when(models.syncFirst(eq(agent), any())).thenReturn(true);

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.read()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(result.providerRead()).isTrue();
        verify(models).syncFirst(agent, new ModelOption("openai-codex", "gpt-5.6-sol"));
        assertThat(agent.model()).isEqualTo("gpt-5.6-sol");
        assertThat(agent.modelSyncedAt()).isNotNull();
        verify(agents).save(agent);
    }

    @Test
    void 같은_모델도_확인_시각은_갱신한다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readOptions(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(new HermesModelOptions("gpt-5.5", "openai-codex"));
        when(models.syncFirst(eq(agent), any())).thenReturn(false);

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.changed()).isFalse();
        assertThat(agent.modelSyncedAt()).isNotNull();
        verify(agents).save(agent);
    }

    @Test
    void 모델을_읽지_못하면_기존_값을_유지하고_1순위를_건드리지_않는다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readOptions(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(null);

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.read()).isFalse();
        assertThat(agent.model()).isEqualTo("gpt-5.5");
        assertThat(agent.modelSyncedAt()).isNull();
        verify(models, never()).syncFirst(any(), any());
    }

    /**
     * Hermes 가 provider 를 주지 않는 판이 있다. 그때는 지금 1순위의 provider 를 그대로 두고 그 사실을
     * 응답에 적는다.
     */
    @Test
    void provider_를_주지_않으면_지금_1순위의_provider_를_그대로_둔다() {
        Agent agent = agent("gpt-5.5");
        when(hermes.readOptions(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(new HermesModelOptions("gpt-5.6-sol", null));
        when(models.optionsOf(agent))
                .thenReturn(List.of(
                        com.bifos.assistant.agent.domain.AgentModelOption.of(
                                1L, 1, new ModelOption("nvidia", "gpt-5.5"))));

        AgentModelSync.SyncResult result = sync.sync(agent);

        assertThat(result.providerRead()).isFalse();
        verify(models).syncFirst(agent, new ModelOption("nvidia", "gpt-5.6-sol"));
    }

    private static Agent agent(String model) {
        return Agent.of("dad", "Dad", "dad", "http://hermes/p/dad", "openai-codex", model,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, 1L);
    }
}
