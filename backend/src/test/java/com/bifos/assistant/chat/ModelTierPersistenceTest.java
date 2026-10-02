package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.chat.application.ModelTierOptions;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.ResolvedModelTier;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.chat.infra.ModelTierGroupSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 저장소에서 그룹 단계 정의를 교체할 때 unique 제약을 지키는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class ModelTierPersistenceTest {

    private static final Agent AGENT = Agent.of(
            "tier-test",
            "tier-test",
            "tier-test",
            "http://hermes.invalid",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.GROUP,
            null, Instant.now());

    private static final Long GROUP_ID = 9_000_000_001L;

    @Autowired
    ModelTierService tiers;

    @Autowired
    ModelTierDefinitionRepository definitions;

    @Autowired
    ModelTierGroupSettingRepository settings;

    @Autowired
    TransactionTemplate transactions;

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> {
            definitions.deleteByGroupId(GROUP_ID);
            settings.deleteById(GROUP_ID);
        });
    }

    @Test
    @DisplayName("같은 그룹의 세 단계 정의를 두 번 저장하면 새 정의로 모두 바뀐다")
    void replacingAllTierDefinitionsDoesNotViolateUniqueConstraint() {
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", GROUP_ID, UserRole.ADMIN);

        tiers.saveGroup(admin, definitions("example-fast", "example-deep"), ModelTier.FAST);
        tiers.saveGroup(admin, definitions("example-deep", "example-fast"), ModelTier.DEEP);

        assertThat(this.definitions.findByGroupIdOrderByTier(GROUP_ID))
                .extracting(definition -> definition.model())
                .containsExactlyInAnyOrder("example-deep", "example-fast", "example-fast");
        assertThat(settings.findById(GROUP_ID).orElseThrow().defaultTier()).isEqualTo(ModelTier.DEEP);
    }

    @Test
    @DisplayName("관리자가 비어 있는 세 단계를 저장하면 배포 mapping보다 profile 기본값을 계속 쓴다")
    void storedFallbackMappingOverridesConfiguredInitialMapping() {
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", GROUP_ID, UserRole.ADMIN);
        List<ModelTierOptions.Tier> fallback = List.of(
                new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", null, null, null),
                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", null, null, null),
                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", null, null, null));

        tiers.saveGroup(admin, fallback, null);
        ResolvedModelTier resolved = tiers.resolveTier(admin, ModelTier.FAST, AGENT);
        ModelTierOptions options = tiers.optionsFor(admin, AGENT);

        assertThat(resolved.tier()).isEqualTo(ModelTier.FAST);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        assertThat(options.tiers()).allSatisfy(tier -> {
            assertThat(tier.provider()).isNull();
            assertThat(tier.model()).isNull();
            assertThat(tier.reasoningEffort()).isNull();
        });
    }

    private static List<ModelTierOptions.Tier> definitions(String fastModel, String deepModel) {
        return List.of(
                new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", "openai-codex", fastModel, "low"),
                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", "openai-codex", "example-fast", "medium"),
                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", "openai-codex", deepModel, "high"));
    }
}
