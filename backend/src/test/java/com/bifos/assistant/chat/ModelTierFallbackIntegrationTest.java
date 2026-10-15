package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.ResolvedModelTier;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 신규 그룹과 기존 빈 단계 정의의 기본 모델 해석을 실제 컨텍스트에서 확인한다. */
@BackendIntegrationTest
class ModelTierFallbackIntegrationTest {

    private static final Agent AGENT = Agent.of(
        "tier-test",
        "tier-test",
        "tier-test",
        "http://hermes.invalid",
        CostMode.SUBSCRIPTION,
        CredentialScope.SHARED_HOUSEHOLD,
        AgentVisibility.GROUP,
        null,
        Instant.now());

    private static final Long GROUP_ID = 9_000_000_002L;

    @Autowired
    ModelTierService tiers;

    @Autowired
    ModelTierDefinitionRepository definitions;

    @Autowired
    TransactionTemplate transactions;


    @BeforeEach
    void clearLeftovers() {
        transactions.executeWithoutResult(status -> definitions.deleteByGroupId(GROUP_ID));
    }


    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> definitions.deleteByGroupId(GROUP_ID));
    }


    @Test
    @DisplayName("정의가 없는 신규 그룹은 세 단계 모두 에이전트 기본 모델로 해석하고 정의를 만들지 않는다")
    void newGroupUsesAgentDefaultsWithoutPersistingDefinitions() {
        Agent configuredAgent = mock(Agent.class);
        ModelChoice defaults = new ModelChoice("example-provider", "example-model", "medium");
        when(configuredAgent.defaultModelProvider()).thenReturn(defaults.provider());
        when(configuredAgent.defaultModel()).thenReturn(defaults.model());
        when(configuredAgent.defaultReasoningEffort()).thenReturn(defaults.reasoningEffort());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", GROUP_ID, UserRole.MEMBER);

        for (ModelTier tier : ModelTier.values()) {
            ResolvedModelTier resolved = tiers.resolveTier(user, tier, configuredAgent);
            assertThat(resolved.tier()).isEqualTo(tier);
            assertThat(resolved.choice()).isEqualTo(defaults);
        }
        assertThat(definitions.findByGroupIdOrderByTier(GROUP_ID)).isEmpty();
    }


    @Test
    @DisplayName("비어 있는 기존 seed 단계는 선택 대화에서도 tier 기록을 남기고 profile 기본값으로 해석한다")
    void nullSeedUsesProfileDefaultWhileKeepingTier() {
        definitions.saveAll(List.of(
            ModelTierDefinition.of(GROUP_ID, ModelTier.FAST, null, null, null),
            ModelTierDefinition.of(GROUP_ID, ModelTier.BALANCED, null, null, null),
            ModelTierDefinition.of(GROUP_ID, ModelTier.DEEP, null, null, null)));
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", GROUP_ID, UserRole.MEMBER);

        for (ModelTier tier : ModelTier.values()) {
            Conversation conversation = mock(Conversation.class);
            when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.TIER);
            when(conversation.modelTier()).thenReturn(tier);

            ResolvedModelTier direct = tiers.resolveTier(user, tier, AGENT);
            ResolvedModelTier fromConversation = tiers.resolve(user, conversation, AGENT);

            assertThat(direct.tier()).isEqualTo(tier);
            assertThat(direct.choice()).isEqualTo(ModelChoice.defaults());
            assertThat(fromConversation.tier()).isEqualTo(tier);
            assertThat(fromConversation.choice()).isEqualTo(ModelChoice.defaults());
        }
    }
}
