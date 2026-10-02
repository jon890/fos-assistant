package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.ModelTierProperties;
import com.bifos.assistant.chat.application.ModelTierSeedImporter;
import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 설정의 단계 초기값이 정의 행이 없는 그룹에만 한 번 옮겨지는지 본다. */
class ModelTierSeedImporterTest {

    private final ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);

    @Test
    @DisplayName("정의 행이 없는 그룹에는 설정의 세 단계를 저장하고 이미 저장한 그룹은 건드리지 않는다")
    void importsOnlyIntoGroupsWithoutDefinitions() {
        List<ModelTierDefinition> saved = new ArrayList<>();
        when(users.findDistinctGroupIds()).thenReturn(List.of(10L, 20L));
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());
        when(definitions.findByGroupIdOrderByTier(20L))
                .thenReturn(List.of(ModelTierDefinition.of(20L, ModelTier.FAST, null, null, null)));
        doAnswer(invocation -> {
                    List<ModelTierDefinition> rows = invocation.getArgument(0);
                    saved.addAll(rows);
                    return rows;
                })
                .when(definitions)
                .saveAll(any());

        new ModelTierSeedImporter(configured(), definitions, users).run(null);

        assertThat(saved).extracting(ModelTierDefinition::groupId).containsOnly(10L);
        assertThat(saved)
                .extracting(ModelTierDefinition::tier)
                .containsExactly(ModelTier.FAST, ModelTier.BALANCED, ModelTier.DEEP);
        assertThat(saved)
                .extracting(ModelTierDefinition::model)
                .containsExactly("example-fast", "example-balanced", "example-deep");
        assertThat(saved).extracting(ModelTierDefinition::provider).containsOnlyNulls();
    }

    @Test
    @DisplayName("설정에 모델이 하나도 없으면 아무것도 읽거나 저장하지 않는다")
    void doesNothingWithoutConfiguredMapping() {
        new ModelTierSeedImporter(new ModelTierProperties(null, null, null), definitions, users).run(null);

        verifyNoInteractions(users);
        verify(definitions, never()).saveAll(any());
    }

    private static ModelTierProperties configured() {
        return new ModelTierProperties(
                new ModelTierProperties.Tier(null, "example-fast", "low"),
                new ModelTierProperties.Tier(null, "example-balanced", "medium"),
                new ModelTierProperties.Tier(null, "example-deep", "high"));
    }
}
