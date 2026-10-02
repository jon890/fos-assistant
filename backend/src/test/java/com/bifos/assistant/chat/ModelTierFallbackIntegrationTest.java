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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** 배포 단계 mapping 이 비어 있을 때 기존 seed 행의 fallback을 실제 컨텍스트에서 확인한다. */
@SpringBootTest(
        properties = {
            "assistant.model-tiers.fast.provider=",
            "assistant.model-tiers.fast.model=",
            "assistant.model-tiers.fast.reasoning-effort=",
            "assistant.model-tiers.balanced.provider=",
            "assistant.model-tiers.balanced.model=",
            "assistant.model-tiers.balanced.reasoning-effort=",
            "assistant.model-tiers.deep.provider=",
            "assistant.model-tiers.deep.model=",
            "assistant.model-tiers.deep.reasoning-effort="
        })
@ActiveProfiles("test")
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

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> definitions.deleteByGroupId(GROUP_ID));
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
