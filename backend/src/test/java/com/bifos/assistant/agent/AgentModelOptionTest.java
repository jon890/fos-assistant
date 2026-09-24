package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.ProviderBlocklist;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentModelOption;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.domain.ProviderState;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.ProviderStateRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 모델 목록을 바꾸는 규칙과 막힌 provider 를 건너뛰는 규칙을 본다. */
@SpringBootTest
@ActiveProfiles("test")
class AgentModelOptionTest {

    private static final String AGENT_CODE = "options";

    @Autowired AgentRepository agents;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ProviderStateRepository providerStates;
    @Autowired AgentModelSelector selector;
    @Autowired ProviderBlocklist blocklist;

    private Agent agent;

    @BeforeEach
    void 준비한다() {
        providerStates.deleteAll();
        modelOptions.deleteAll();
        agents.findByCode(AGENT_CODE).ifPresent(agents::delete);
        agent = agents.save(Agent.of(
                AGENT_CODE,
                "목록",
                AGENT_CODE,
                "http://agent-runtime.test/p/" + AGENT_CODE,
                "openai-codex",
                "example-model",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L));
    }

    @Test
    void 받은_순서가_그대로_순위가_된다() {
        selector.replace(
                agent,
                List.of(new ModelOption("nvidia", "example-model-b"), new ModelOption("openai-codex", "example-model")));

        assertThat(selector.optionsOf(agent))
                .extracting(AgentModelOption::rank, AgentModelOption::provider)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "nvidia"),
                        org.assertj.core.groups.Tuple.tuple(2, "openai-codex"));
    }

    @Test
    void 빈_목록은_거절한다() {
        selector.seedFirst(agent, new ModelOption("openai-codex", "example-model"));

        assertThatThrownBy(() -> selector.replace(agent, List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(selector.optionsOf(agent)).hasSize(1);
    }

    @Test
    void provider_나_모델이_비면_거절한다() {
        assertThatThrownBy(() -> selector.replace(agent, List.of(new ModelOption("nvidia", " "))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 막힌_provider_는_쓸_수_있는_목록에서_빠진다() {
        selector.replace(
                agent,
                List.of(new ModelOption("openai-codex", "example-model"), new ModelOption("nvidia", "example-model-b")));
        blocklist.block("openai-codex", "provider authentication failed");

        assertThat(selector.availableFor(agent))
                .containsExactly(new ModelOption("nvidia", "example-model-b"));
        assertThat(blocklist.blockedProviders()).containsExactly("openai-codex");
    }

    /** 식는 시간이 지난 줄은 남아 있어도 막힌 것으로 세지 않는다. */
    @Test
    void 식는_시간이_지나면_다시_1순위부터_시도한다() {
        selector.replace(
                agent,
                List.of(new ModelOption("openai-codex", "example-model"), new ModelOption("nvidia", "example-model-b")));
        providerStates.save(ProviderState.blocked(
                "openai-codex", Instant.now().minus(1, ChronoUnit.MINUTES), "지난 막힘"));

        assertThat(selector.availableFor(agent))
                .first()
                .isEqualTo(new ModelOption("openai-codex", "example-model"));
        assertThat(blocklist.blocked()).isEmpty();
    }

    @Test
    void 막힘을_풀면_다시_쓸_수_있다() {
        selector.seedFirst(agent, new ModelOption("openai-codex", "example-model"));
        blocklist.block("openai-codex", "provider authentication failed");

        blocklist.release("openai-codex");

        assertThat(selector.availableFor(agent)).hasSize(1);
        assertThat(blocklist.blocked()).isEmpty();
    }
}
