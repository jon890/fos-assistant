package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ModelTierOptions;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.chat.infra.ModelTierGroupSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.user.domain.UserRole;
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

        tiers.saveGroup(admin, definitions("gpt-6-luna", "gpt-6.1-sol"), ModelTier.FAST);
        tiers.saveGroup(admin, definitions("gpt-6.1-sol", "gpt-6-luna"), ModelTier.DEEP);

        assertThat(this.definitions.findByGroupIdOrderByTier(GROUP_ID))
                .extracting(definition -> definition.model())
                .containsExactlyInAnyOrder("gpt-6.1-sol", "gpt-6-luna", "gpt-6-luna");
        assertThat(settings.findById(GROUP_ID).orElseThrow().defaultTier()).isEqualTo(ModelTier.DEEP);
    }

    private static List<ModelTierOptions.Tier> definitions(String fastModel, String deepModel) {
        return List.of(
                new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", "openai-codex", fastModel, "low"),
                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", "openai-codex", "gpt-6-luna", "medium"),
                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", "openai-codex", deepModel, "high"));
    }
}
