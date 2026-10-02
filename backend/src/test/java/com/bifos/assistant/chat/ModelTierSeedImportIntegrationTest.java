package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ModelTierSeedImporter;
import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** 설정의 단계 초기값이 실제 저장소와 실제 빈에서 한 번만 옮겨지는지 본다. */
@SpringBootTest(
        properties = {
            "assistant.model-tiers.fast.model=example-fast",
            "assistant.model-tiers.fast.reasoning-effort=low",
            "assistant.model-tiers.balanced.provider=example-provider",
            "assistant.model-tiers.balanced.model=example-balanced",
            "assistant.model-tiers.balanced.reasoning-effort=medium"
        })
@ActiveProfiles("test")
class ModelTierSeedImportIntegrationTest {

    private static final Long GROUP_ID = 9_000_000_005L;
    private static final String EMAIL = "seed-import@example.com";

    @Autowired
    ModelTierSeedImporter importer;

    @Autowired
    ModelTierDefinitionRepository definitions;

    @Autowired
    AppUserRepository users;

    @Autowired
    TransactionTemplate transactions;

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> {
            definitions.deleteByGroupId(GROUP_ID);
            users.findByEmail(EMAIL).ifPresent(users::delete);
        });
    }

    @Test
    @DisplayName("사용자가 있는 그룹에 정의 행이 없으면 설정의 세 단계를 저장하고, 다시 돌려도 저장된 정의를 바꾸지 않는다")
    void importsOnceIntoRealTables() throws Exception {
        users.save(AppUser.of(EMAIL, "초기값 이전", GROUP_ID, UserRole.ADMIN, Instant.now()));

        importer.run(null);

        List<ModelTierDefinition> imported = definitions.findByGroupIdOrderByTier(GROUP_ID);
        assertThat(imported).hasSize(3);
        assertThat(imported)
                .filteredOn(definition -> definition.tier() == ModelTier.FAST)
                .singleElement()
                .satisfies(fast -> {
                    assertThat(fast.provider()).isNull();
                    assertThat(fast.model()).isEqualTo("example-fast");
                    assertThat(fast.reasoningEffort()).isEqualTo("low");
                });
        assertThat(imported)
                .filteredOn(definition -> definition.tier() == ModelTier.BALANCED)
                .singleElement()
                .satisfies(balanced -> assertThat(balanced.provider()).isEqualTo("example-provider"));
        assertThat(imported)
                .filteredOn(definition -> definition.tier() == ModelTier.DEEP)
                .singleElement()
                .satisfies(deep -> assertThat(deep.model()).isNull());
        List<Long> ids = imported.stream().map(ModelTierDefinition::id).toList();

        importer.run(null);

        assertThat(definitions.findByGroupIdOrderByTier(GROUP_ID))
                .extracting(ModelTierDefinition::id)
                .containsExactlyInAnyOrderElementsOf(ids);
    }
}
