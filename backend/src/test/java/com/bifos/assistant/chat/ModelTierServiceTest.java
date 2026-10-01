package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ModelOptions;
import com.bifos.assistant.chat.application.ModelOptionsService;
import com.bifos.assistant.chat.application.ModelTierOptions;
import com.bifos.assistant.chat.application.ModelTierProperties;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.ResolvedModelTier;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.chat.domain.ModelTierGroupSetting;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.chat.infra.ModelTierGroupSettingRepository;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 단계 정의 저장의 관리자 경계와 실행 단계 우선순위를 확인한다. */
class ModelTierServiceTest {

    @Test
    @DisplayName("DEFAULT 대화는 사용자와 그룹 기본 단계가 있어도 profile 기본값만 쓴다")
    void explicitDefaultBypassesUserAndGroupDefaults() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.DEFAULT);

        ResolvedModelTier resolved = service.resolve(user, conversation, mock(Agent.class));

        assertThat(resolved.choice().usesDefaultModel()).isTrue();
        assertThat(resolved.tier()).isNull();
        verifyNoInteractions(definitions, settings, users, options);
    }

    @Test
    @DisplayName("DB 정의가 없는 새 그룹은 저장하지 않고 초기 세 단계를 순서대로 돌려준다")
    void newGroupGetsReadOnlyInitialDefinitionsInOrder() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Agent agent = mock(Agent.class);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());
        when(options.optionsForAgent(agent))
                .thenReturn(new ModelOptions("openai-codex", "example-fast", List.of(), List.of()));
        when(users.findById(1L)).thenReturn(Optional.empty());
        when(settings.findById(10L)).thenReturn(Optional.empty());

        ModelTierOptions result = service.optionsFor(user, agent);

        assertThat(result.tiers())
                .extracting(ModelTierOptions.Tier::tier)
                .containsExactly(ModelTier.FAST, ModelTier.BALANCED, ModelTier.DEEP);
        assertThat(result.tiers())
                .extracting(ModelTierOptions.Tier::model)
                .containsExactly("example-fast", "example-balanced", "example-deep");
        assertThat(result.tiers())
                .extracting(ModelTierOptions.Tier::reasoningEffort)
                .containsExactly("low", "medium", "high");
        verify(definitions, never()).save(any());
    }

    @Test
    @DisplayName("일부만 저장된 단계 정의는 초기값과 섞지 않고 거절한다")
    void rejectsIncompleteStoredDefinitions() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        when(definitions.findByGroupIdOrderByTier(10L))
                .thenReturn(List.of(ModelTierDefinition.of(10L, ModelTier.FAST, null, "example-fast", "low")));

        assertThatThrownBy(() -> service.requireDefined(user, ModelTier.FAST))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("관리자가 아니면 그룹 단계 정의를 바꾸지 못한다")
    void memberCannotSaveGroupModelTiers() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser member = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);

        assertThatThrownBy(() -> service.saveGroup(member, List.of(), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(definitions, settings, users, options);
    }

    @Test
    @DisplayName("세 단계가 모두 없으면 관리자도 그룹 단계를 저장하지 못한다")
    void rejectsIncompleteDefinitions() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);
        List<ModelTierOptions.Tier> incomplete =
                List.of(new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", null, "example-fast", "low"));

        assertThatThrownBy(() -> service.saveGroup(admin, incomplete, ModelTier.FAST))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(definitions, settings, users, options);
    }

    @Test
    @DisplayName("그룹 단계 저장은 provider와 model의 앞뒤 공백을 없애고 빈 provider는 null로 둔다")
    void saveGroupNormalizesProviderAndModelBeforePersisting() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);
        doAnswer(invocation -> {
                    List<ModelTierDefinition> saved = invocation.getArgument(0);
                    assertThat(saved)
                            .satisfiesExactly(
                                    fast -> {
                                        assertThat(fast.provider()).isNull();
                                        assertThat(fast.model()).isEqualTo("example-fast");
                                    },
                                    balanced -> {
                                        assertThat(balanced.provider()).isEqualTo("openai-codex");
                                        assertThat(balanced.model()).isEqualTo("example-balanced");
                                    },
                                    deep -> {
                                        assertThat(deep.provider()).isEqualTo("openai-codex");
                                        assertThat(deep.model()).isEqualTo("example-deep");
                                    });
                    return saved;
                })
                .when(definitions)
                .saveAll(any());

        service.saveGroup(
                admin,
                List.of(
                        new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", "   ", " example-fast ", "low"),
                        new ModelTierOptions.Tier(
                                ModelTier.BALANCED, "균형", " openai-codex ", " example-balanced ", "medium"),
                        new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", " openai-codex ", " example-deep ", "high")),
                ModelTier.FAST);
    }

    @Test
    @DisplayName("관리자는 provider만 또는 effort만 있는 부분 단계 mapping을 저장하지 못한다")
    void rejectsPartialTierMappings() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);

        assertThatThrownBy(() -> service.saveGroup(
                        admin,
                        List.of(
                                new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", "openai-codex", null, null),
                                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", null, null, null),
                                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", null, null, null)),
                        null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.saveGroup(
                        admin,
                        List.of(
                                new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", null, "example-fast", null),
                                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", null, null, null),
                                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", null, null, null)),
                        null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.saveGroup(
                        admin,
                        List.of(
                                new ModelTierOptions.Tier(ModelTier.FAST, "빠르게", null, null, "low"),
                                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", null, null, null),
                                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", null, null, null)),
                        null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("배포 단계 설정은 provider만 또는 model만 채운 부분 mapping으로 기동하지 못한다")
    void rejectsPartialConfiguredTierMapping() {
        assertThatThrownBy(() -> new ModelTierProperties.Tier("openai-codex", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model and reasoning-effort");
        assertThatThrownBy(() -> new ModelTierProperties.Tier(null, "example-fast", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model and reasoning-effort");
    }

    @Test
    @DisplayName("배포 단계 설정은 지원하지 않는 effort와 칸 길이를 넘는 값을 기동 전에 거절한다")
    void rejectsInvalidConfiguredTierValues() {
        assertThatThrownBy(() -> new ModelTierProperties.Tier(null, "example-fast", "banana"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reasoning-effort");
        assertThatThrownBy(() -> new ModelTierProperties.Tier(
                        "p".repeat(ModelChoice.PROVIDER_MAX_LENGTH + 1), "example-fast", "low"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider");
        assertThatThrownBy(
                        () -> new ModelTierProperties.Tier(null, "m".repeat(ModelChoice.MODEL_MAX_LENGTH + 1), "low"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
    }

    @Test
    @DisplayName("설정과 저장 단계가 모두 비어 있으면 단계 기록을 남기고 profile 기본값으로 돈다")
    void emptyTierMappingFallsBackToProfileDefault() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service =
                new ModelTierService(definitions, settings, users, options, new ModelTierProperties(null, null, null));
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());

        ResolvedModelTier resolved = service.resolveTier(user, ModelTier.FAST, mock(Agent.class));

        assertThat(resolved.tier()).isEqualTo(ModelTier.FAST);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        verifyNoInteractions(options);
    }

    @Test
    @DisplayName("각 단계는 요청 profile의 catalog에서 따로 검증한 설정 mapping을 쓴다")
    void configuredTiersUseEachProfilesCatalog() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Agent fastAgent = mock(Agent.class);
        Agent balancedAgent = mock(Agent.class);
        Agent deepAgent = mock(Agent.class);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());
        when(options.optionsForAgent(fastAgent)).thenReturn(catalog("fast-provider", "example-fast"));
        when(options.optionsForAgent(balancedAgent)).thenReturn(catalog("balanced-provider", "example-balanced"));
        when(options.optionsForAgent(deepAgent)).thenReturn(catalog("deep-provider", "example-deep"));

        ResolvedModelTier fast = service.resolveTier(user, ModelTier.FAST, fastAgent);
        ResolvedModelTier balanced = service.resolveTier(user, ModelTier.BALANCED, balancedAgent);
        ResolvedModelTier deep = service.resolveTier(user, ModelTier.DEEP, deepAgent);

        assertThat(fast.choice()).isEqualTo(ModelChoice.of("fast-provider", "example-fast", "low"));
        assertThat(balanced.choice()).isEqualTo(ModelChoice.of("balanced-provider", "example-balanced", "medium"));
        assertThat(deep.choice()).isEqualTo(ModelChoice.of("deep-provider", "example-deep", "high"));
    }

    @Test
    @DisplayName("설정 단계 모델이 요청 profile의 catalog에 없으면 실행 전에 거절한다")
    void rejectsConfiguredTierMissingFromAgentCatalog() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Agent agent = mock(Agent.class);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());
        when(options.optionsForAgent(agent)).thenReturn(catalog("fast-provider", "another-model"));

        assertThatThrownBy(() -> service.resolveTier(user, ModelTier.FAST, agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("관리자가 비어 있는 세 단계를 저장하면 catalog 조회 없이 profile 기본값 안내를 돌려준다")
    void storedFallbackTiersDoNotReadCatalogForOptions() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, properties());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.ADMIN);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(fallbackDefinitions());
        when(users.findById(1L)).thenReturn(Optional.empty());
        when(settings.findById(10L)).thenReturn(Optional.empty());

        ModelTierOptions result = service.optionsFor(user, mock(Agent.class));

        assertThat(result.tiers()).allSatisfy(tier -> {
            assertThat(tier.provider()).isNull();
            assertThat(tier.model()).isNull();
            assertThat(tier.reasoningEffort()).isNull();
        });
        verifyNoInteractions(options);
    }

    @Test
    @DisplayName("상속 대화는 사용자 기본 단계를 그룹 기본 단계보다 먼저 쓴다")
    void inheritedConversationPrefersUserDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        AppUser appUser = mock(AppUser.class);
        when(appUser.modelDefaultTier()).thenReturn("FAST");
        when(fixtures.users.findById(1L)).thenReturn(Optional.of(appUser));
        when(fixtures.settings.findById(10L)).thenReturn(Optional.of(ModelTierGroupSetting.of(10L, ModelTier.DEEP)));
        Conversation conversation = mock(Conversation.class);

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, conversation, fixtures.agent);

        assertThat(resolved.tier()).isEqualTo(ModelTier.FAST);
        assertThat(resolved.choice().model()).isEqualTo("example-fast");
        assertThat(resolved.choice().reasoningEffort()).isEqualTo("low");
    }

    @Test
    @DisplayName("사용자 기본 단계가 없으면 상속 대화는 그룹 기본 단계를 쓴다")
    void inheritedConversationFallsBackToGroupDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        AppUser appUser = mock(AppUser.class);
        when(appUser.modelDefaultTier()).thenReturn(null);
        when(fixtures.users.findById(1L)).thenReturn(Optional.of(appUser));
        when(fixtures.settings.findById(10L)).thenReturn(Optional.of(ModelTierGroupSetting.of(10L, ModelTier.DEEP)));
        Conversation conversation = mock(Conversation.class);

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, conversation, fixtures.agent);

        assertThat(resolved.tier()).isEqualTo(ModelTier.DEEP);
        assertThat(resolved.choice().model()).isEqualTo("example-deep");
        assertThat(resolved.choice().reasoningEffort()).isEqualTo("high");
    }

    @Test
    @DisplayName("명시 단계는 사용자와 그룹 기본 단계보다 먼저 쓴다")
    void tierConversationOverridesUserAndGroupDefaults() {
        TierFixtures fixtures = tierFixtures();
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.TIER);
        when(conversation.modelTier()).thenReturn(ModelTier.BALANCED);

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, conversation, fixtures.agent);

        assertThat(resolved.tier()).isEqualTo(ModelTier.BALANCED);
        assertThat(resolved.choice().reasoningEffort()).isEqualTo("medium");
        verifyNoInteractions(fixtures.users, fixtures.settings);
    }

    @Test
    @DisplayName("CUSTOM 대화는 저장한 모델 선택을 단계 정의와 무관하게 보존한다")
    void customConversationPreservesStoredChoice() {
        TierFixtures fixtures = tierFixtures();
        Conversation conversation = mock(Conversation.class);
        ModelChoice choice = ModelChoice.stored("openai-codex", "custom-model", "max");
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.CUSTOM);
        when(conversation.modelChoice()).thenReturn(choice);

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, conversation, fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(choice);
        assertThat(resolved.tier()).isNull();
        verifyNoInteractions(fixtures.definitions, fixtures.settings, fixtures.users, fixtures.options);
    }

    @Test
    @DisplayName("실행에 넘길 단계 선택은 뒤의 단계 정의 변경에 영향받지 않는다")
    void resolvedTierChoiceIsImmutableSnapshot() {
        TierFixtures fixtures = tierFixtures();

        ResolvedModelTier resolved = fixtures.service.resolveTier(fixtures.user, ModelTier.FAST, fixtures.agent);
        ModelTierDefinition changed =
                ModelTierDefinition.of(10L, ModelTier.FAST, "openai-codex", "example-deep", "high");
        when(fixtures.definitions.findByGroupIdOrderByTier(10L))
                .thenReturn(List.of(
                        changed,
                        ModelTierDefinition.of(10L, ModelTier.BALANCED, null, "example-balanced", "medium"),
                        ModelTierDefinition.of(10L, ModelTier.DEEP, null, "example-deep", "high")));

        ResolvedModelTier updated = fixtures.service.resolveTier(fixtures.user, ModelTier.FAST, fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-fast", "low"));
        assertThat(updated.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-deep", "high"));
    }

    private static TierFixtures tierFixtures() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        Agent agent = mock(Agent.class);
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        when(definitions.findByGroupIdOrderByTier(10L))
                .thenReturn(List.of(
                        ModelTierDefinition.of(10L, ModelTier.FAST, null, "example-fast", "low"),
                        ModelTierDefinition.of(10L, ModelTier.BALANCED, null, "example-balanced", "medium"),
                        ModelTierDefinition.of(10L, ModelTier.DEEP, null, "example-deep", "high")));
        when(options.optionsForAgent(agent))
                .thenReturn(new ModelOptions(
                        "openai-codex",
                        "example-fast",
                        List.of(new HermesModelCatalog.Provider(
                                "openai-codex",
                                "OpenAI",
                                List.of("example-fast", "example-balanced", "example-deep"),
                                Map.of())),
                        List.of()));
        return new TierFixtures(
                new ModelTierService(definitions, settings, users, options, properties()),
                definitions,
                settings,
                users,
                options,
                user,
                agent);
    }

    private record TierFixtures(
            ModelTierService service,
            ModelTierDefinitionRepository definitions,
            ModelTierGroupSettingRepository settings,
            AppUserRepository users,
            ModelOptionsService options,
            CurrentUser user,
            Agent agent) {}

    private static ModelTierProperties properties() {
        return new ModelTierProperties(
                new ModelTierProperties.Tier(null, "example-fast", "low"),
                new ModelTierProperties.Tier(null, "example-balanced", "medium"),
                new ModelTierProperties.Tier(null, "example-deep", "high"));
    }

    private static ModelOptions catalog(String provider, String model) {
        return new ModelOptions(
                provider,
                model,
                List.of(new HermesModelCatalog.Provider(provider, provider, List.of(model), Map.of())),
                List.of());
    }

    private static List<ModelTierDefinition> fallbackDefinitions() {
        return List.of(
                ModelTierDefinition.of(10L, ModelTier.FAST, null, null, null),
                ModelTierDefinition.of(10L, ModelTier.BALANCED, null, null, null),
                ModelTierDefinition.of(10L, ModelTier.DEEP, null, null, null));
    }
}
