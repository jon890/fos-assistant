package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.GroupModelTiers;
import com.bifos.assistant.chat.application.HiddenModels;
import com.bifos.assistant.chat.application.ModelOptions;
import com.bifos.assistant.chat.application.ModelOptionsService;
import com.bifos.assistant.chat.application.ModelTierOptions;
import com.bifos.assistant.chat.application.ModelTierProperties;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.application.ModelVisibilityService;
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
import com.bifos.assistant.user.domain.type.UserRole;
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
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.DEFAULT);

        ResolvedModelTier resolved = service.resolve(user, conversation, mock(Agent.class));

        assertThat(resolved.choice().usesDefaultModel()).isTrue();
        assertThat(resolved.tier()).isNull();
        verifyNoInteractions(definitions, settings, users, options);
    }

    @Test
    @DisplayName("DB 정의가 없는 새 그룹은 저장하지 않고 빈 세 단계를 순서대로 돌려준다")
    void newGroupGetsReadOnlyEmptyDefinitionsInOrder() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());
        when(users.findById(1L)).thenReturn(Optional.empty());
        when(settings.findById(10L)).thenReturn(Optional.empty());

        ModelTierOptions result = service.optionsFor(user, mock(Agent.class));

        assertThat(result.tiers())
                .extracting(ModelTierOptions.Tier::tier)
                .containsExactly(ModelTier.FAST, ModelTier.BALANCED, ModelTier.DEEP);
        assertThat(result.tiers()).allSatisfy(tier -> {
            assertThat(tier.model()).isNull();
            assertThat(tier.reasoningEffort()).isNull();
        });
        verify(definitions, never()).save(any());
        verifyNoInteractions(options);
    }

    @Test
    @DisplayName("MEMBER 역할이 받는 단계에는 provider 와 모델과 effort 가 비고 단계와 이름과 기본값은 남는다")
    void memberGetsTiersWithoutProviderModelOrEffort() {
        TierFixtures fixtures = tierFixtures();
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());
        when(fixtures.settings().findById(10L))
                .thenReturn(Optional.of(ModelTierGroupSetting.of(10L, ModelTier.BALANCED)));

        ModelTierOptions result = fixtures.service().optionsFor(fixtures.user(), fixtures.agent());

        assertThat(result.tiers())
                .extracting(ModelTierOptions.Tier::tier, ModelTierOptions.Tier::label)
                .containsExactly(
                        tuple(ModelTier.FAST, "빠르게"), tuple(ModelTier.BALANCED, "균형"), tuple(ModelTier.DEEP, "깊게"));
        assertThat(result.tiers()).allSatisfy(tier -> {
            assertThat(tier.provider()).as("provider").isNull();
            assertThat(tier.model()).as("model").isNull();
            assertThat(tier.reasoningEffort()).as("reasoningEffort").isNull();
        });
        assertThat(result.groupDefaultTier()).isEqualTo(ModelTier.BALANCED);
        assertThat(result.admin()).isFalse();
        // 싣지 않을 provider 를 알아내려고 Hermes 목록을 읽지 않는다.
        verifyNoInteractions(fixtures.options());
    }

    @Test
    @DisplayName("ADMIN 역할이 받는 단계에는 provider 와 모델과 effort 가 그대로 실린다")
    void adminGetsTiersWithProviderModelAndEffort() {
        TierFixtures fixtures = tierFixtures();
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());
        when(fixtures.settings().findById(10L)).thenReturn(Optional.empty());

        ModelTierOptions result = fixtures.service().optionsFor(admin, fixtures.agent());

        assertThat(result.tiers())
                .extracting(
                        ModelTierOptions.Tier::tier,
                        ModelTierOptions.Tier::provider,
                        ModelTierOptions.Tier::model,
                        ModelTierOptions.Tier::reasoningEffort)
                .containsExactly(
                        tuple(ModelTier.FAST, "openai-codex", "example-fast", "low"),
                        tuple(ModelTier.BALANCED, "openai-codex", "example-balanced", "medium"),
                        tuple(ModelTier.DEEP, "openai-codex", "example-deep", "high"));
        assertThat(result.admin()).isTrue();
    }

    @Test
    @DisplayName("관리자는 에이전트 없이 그룹 단계를 읽고 provider 를 비운 단계는 비운 채 받는다")
    void adminReadsGroupTiersWithoutAgentAndBlankProviderStaysBlank() {
        TierFixtures fixtures = tierFixtures();
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);
        when(fixtures.settings().findById(10L))
                .thenReturn(Optional.of(ModelTierGroupSetting.of(10L, ModelTier.BALANCED)));

        GroupModelTiers result = fixtures.service().groupTiers(admin);

        assertThat(result.tiers())
                .extracting(
                        ModelTierOptions.Tier::tier,
                        ModelTierOptions.Tier::provider,
                        ModelTierOptions.Tier::model,
                        ModelTierOptions.Tier::reasoningEffort)
                .containsExactly(
                        tuple(ModelTier.FAST, null, "example-fast", "low"),
                        tuple(ModelTier.BALANCED, null, "example-balanced", "medium"),
                        tuple(ModelTier.DEEP, null, "example-deep", "high"));
        assertThat(result.groupDefaultTier()).isEqualTo(ModelTier.BALANCED);
        // 에이전트의 목록을 읽지 않는다. 관리자가 시작할 수 없는 에이전트만 있어도 그룹 단계를 읽는다.
        verifyNoInteractions(fixtures.options());
    }

    @Test
    @DisplayName("MEMBER 역할은 그룹 단계의 관리자 조회를 받지 못한다")
    void memberCannotReadGroupTiers() {
        TierFixtures fixtures = tierFixtures();

        assertThatThrownBy(() -> fixtures.service().groupTiers(fixtures.user()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관리자가 아니면 그룹 단계 정의를 바꾸지 못한다")
    void memberCannotSaveGroupModelTiers() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
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
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
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
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
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
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
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
    @DisplayName("그룹 단계 정의는 effort none 을 받지 않는다")
    void rejectsNoneEffortInGroupTierDefinition() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);

        assertThatThrownBy(() -> service.saveGroup(
                        admin,
                        List.of(
                                new ModelTierOptions.Tier(
                                        ModelTier.FAST, "빠르게", "openai-codex", "example-fast", "none"),
                                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", null, null, null),
                                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", null, null, null)),
                        null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(definitions, never()).saveAll(any());
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
    @DisplayName("저장 단계와 에이전트 기본값이 모두 비어 있으면 단계 기록을 남기고 profile 기본값으로 돈다")
    void emptyTierMappingFallsBackToProfileDefault() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(List.of());

        ResolvedModelTier resolved = service.resolveTier(user, ModelTier.FAST, mock(Agent.class));

        assertThat(resolved.tier()).isEqualTo(ModelTier.FAST);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        verifyNoInteractions(options);
    }

    @Test
    @DisplayName("provider를 비운 단계는 요청 profile의 catalog에서 따로 검증하고 그 profile의 provider를 쓴다")
    void storedTiersUseEachProfilesCatalog() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Agent fastAgent = mock(Agent.class);
        Agent balancedAgent = mock(Agent.class);
        Agent deepAgent = mock(Agent.class);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(storedDefinitions());
        when(options.optionsForAgent(10L, fastAgent)).thenReturn(catalog("fast-provider", "example-fast"));
        when(options.optionsForAgent(10L, balancedAgent)).thenReturn(catalog("balanced-provider", "example-balanced"));
        when(options.optionsForAgent(10L, deepAgent)).thenReturn(catalog("deep-provider", "example-deep"));

        ResolvedModelTier fast = service.resolveTier(user, ModelTier.FAST, fastAgent);
        ResolvedModelTier balanced = service.resolveTier(user, ModelTier.BALANCED, balancedAgent);
        ResolvedModelTier deep = service.resolveTier(user, ModelTier.DEEP, deepAgent);

        assertThat(fast.choice()).isEqualTo(ModelChoice.of("fast-provider", "example-fast", "low"));
        assertThat(balanced.choice()).isEqualTo(ModelChoice.of("balanced-provider", "example-balanced", "medium"));
        assertThat(deep.choice()).isEqualTo(ModelChoice.of("deep-provider", "example-deep", "high"));
    }

    @Test
    @DisplayName("단계 모델이 요청 profile의 catalog에 없으면 실행 전에 거절한다")
    void rejectsStoredTierMissingFromAgentCatalog() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelTierGroupSettingRepository settings = mock(ModelTierGroupSettingRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Agent agent = mock(Agent.class);
        when(definitions.findByGroupIdOrderByTier(10L)).thenReturn(storedDefinitions());
        when(options.optionsForAgent(10L, agent)).thenReturn(catalog("fast-provider", "another-model"));

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
        ModelTierService service = new ModelTierService(definitions, settings, users, options, nothingHidden());
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

    @Test
    @DisplayName("DEFAULT 대화는 에이전트 기본 모델이 있으면 그 값을 명시해 보낸다")
    void explicitDefaultUsesAgentDefaultModel() {
        TierFixtures fixtures = tierFixtures();
        agentDefault(fixtures.agent, "openai-codex", "example-agent", "high");
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.DEFAULT);

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, conversation, fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.stored("openai-codex", "example-agent", "high"));
        assertThat(resolved.tier()).isNull();
        verifyNoInteractions(fixtures.options);
    }

    @Test
    @DisplayName("선택 없는 대화는 사용자와 그룹 기본 단계가 없으면 에이전트 기본 모델로 돈다")
    void inheritedConversationFallsBackToAgentDefaultModel() {
        TierFixtures fixtures = tierFixtures();
        agentDefault(fixtures.agent, "openai-codex", "example-agent", null);
        when(fixtures.users.findById(1L)).thenReturn(Optional.empty());
        when(fixtures.settings.findById(10L)).thenReturn(Optional.empty());

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, mock(Conversation.class), fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.stored("openai-codex", "example-agent", null));
        assertThat(resolved.tier()).isNull();
    }

    @Test
    @DisplayName("mapping이 빈 단계는 단계 기록을 남기고 에이전트 기본 모델로 돈다")
    void emptyTierMappingUsesAgentDefaultModel() {
        TierFixtures fixtures = tierFixtures();
        agentDefault(fixtures.agent, "openai-codex", "example-agent", "medium");
        when(fixtures.definitions.findByGroupIdOrderByTier(10L)).thenReturn(fallbackDefinitions());

        ResolvedModelTier resolved = fixtures.service.resolveTier(fixtures.user, ModelTier.DEEP, fixtures.agent);

        assertThat(resolved.tier()).isEqualTo(ModelTier.DEEP);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.stored("openai-codex", "example-agent", "medium"));
    }

    @Test
    @DisplayName("모델을 비운 CUSTOM 대화는 에이전트 기본 모델로 돌고 effort는 대화가 고른 값이 먼저다")
    void customConversationWithoutModelUsesAgentDefaultModel() {
        TierFixtures fixtures = tierFixtures();
        agentDefault(fixtures.agent, "openai-codex", "example-agent", "low");
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.CUSTOM);
        when(conversation.modelChoice()).thenReturn(ModelChoice.stored(null, null, "max"));

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, conversation, fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.stored("openai-codex", "example-agent", "max"));
    }

    @Test
    @DisplayName("해석한 모델이 숨긴 모델이면 다른 모델로 바꾸지 않고 MODEL_HIDDEN 으로 거절한다")
    void hiddenModelIsRejectedInsteadOfReplaced() {
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        ModelTierService service = new ModelTierService(
                mock(ModelTierDefinitionRepository.class),
                mock(ModelTierGroupSettingRepository.class),
                mock(AppUserRepository.class),
                mock(ModelOptionsService.class),
                visibility);
        CurrentUser user = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);
        Conversation conversation = mock(Conversation.class);
        ModelChoice choice = ModelChoice.stored("openai-codex", "hidden-model", null);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.CUSTOM);
        when(conversation.modelChoice()).thenReturn(choice);
        when(visibility.hiddenFor(10L))
                .thenReturn(new HiddenModels(List.of(new HiddenModels.Entry("openai-codex", "hidden-model"))));

        assertThatThrownBy(() -> service.resolve(user, conversation, mock(Agent.class)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }

    @Test
    @DisplayName("숨긴 provider를 명시한 단계 정의는 저장하지 못한다")
    void hiddenProviderCannotBeSavedAsTierDefinition() {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        ModelTierService service = new ModelTierService(
                definitions,
                mock(ModelTierGroupSettingRepository.class),
                mock(AppUserRepository.class),
                mock(ModelOptionsService.class),
                visibility);
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);
        doThrow(new ApiException(ErrorCode.MODEL_HIDDEN, "hidden"))
                .when(visibility)
                .requireVisible(10L, ModelChoice.stored("hidden-provider", "example-fast", null));

        assertThatThrownBy(() -> service.saveGroup(
                        admin,
                        List.of(
                                new ModelTierOptions.Tier(
                                        ModelTier.FAST, "빠르게", "hidden-provider", "example-fast", "low"),
                                new ModelTierOptions.Tier(ModelTier.BALANCED, "균형", null, null, null),
                                new ModelTierOptions.Tier(ModelTier.DEEP, "깊게", null, null, null)),
                        null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
        verifyNoInteractions(definitions);
    }

    @Test
    @DisplayName("숨김이 없으면 모델을 싣지 않는 해석은 목록을 읽지 않고 통과한다")
    void resolvesDefaultWithoutReadingCatalogWhenNothingIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(HiddenModels.none());

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        verifyNoInteractions(fixtures.options);
    }

    @Test
    @DisplayName("에이전트 기본 모델이 없고 profile 의 기본 모델이 숨긴 모델이면 MODEL_HIDDEN 으로 거절한다")
    void rejectsDefaultRunWhenProfileDefaultModelIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        when(fixtures.options.profileDefaultOf(fixtures.agent))
                .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        assertThatThrownBy(() -> fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }

    @Test
    @DisplayName("에이전트 기본 모델이 없고 profile 의 기본 provider 전체를 숨겼으면 MODEL_HIDDEN 으로 거절한다")
    void rejectsDefaultRunWhenProfileDefaultProviderIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", null));
        when(fixtures.options.profileDefaultOf(fixtures.agent))
                .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        assertThatThrownBy(() -> fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }

    @Test
    @DisplayName("숨김이 있어도 profile 의 기본 모델이 숨기지 않은 모델이면 세 값을 비운 채 통과한다")
    void passesDefaultRunWhenProfileDefaultModelIsNotHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-other"));
        when(fixtures.options.profileDefaultOf(fixtures.agent))
                .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }

    @Test
    @DisplayName("숨김이 있는데 목록을 한 번도 읽지 못했으면 통과시키지 않고 HERMES_UNAVAILABLE 로 거절한다")
    void rejectsDefaultRunWhenCatalogWasNeverRead() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        when(fixtures.options.profileDefaultOf(fixtures.agent))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }

    @Test
    @DisplayName("숨김이 있어도 에이전트 기본 모델이 있으면 목록을 읽지 않고 그 모델로 판정한다")
    void judgesAgentDefaultModelWithoutReadingCatalog() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        agentDefault(fixtures.agent, "example-provider", "example-agent", null);

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.stored("example-provider", "example-agent", null));
        verifyNoInteractions(fixtures.options);
    }

    @Test
    @DisplayName("숨김이 있고 에이전트 기본 모델이 숨긴 모델이면 목록을 읽지 않고 MODEL_HIDDEN 으로 거절한다")
    void rejectsHiddenAgentDefaultModelWithoutReadingCatalog() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-agent"));
        agentDefault(fixtures.agent, "example-provider", "example-agent", null);

        assertThatThrownBy(() -> fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
        verifyNoInteractions(fixtures.options);
    }

    @Test
    @DisplayName("Hermes 가 profile 의 기본 모델을 주지 않으면 판정할 값이 없어 통과한다")
    void passesDefaultRunWhenHermesGivesNoDefaultModel() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", null));
        when(fixtures.options.profileDefaultOf(fixtures.agent))
                .thenReturn(ModelChoice.stored("example-provider", null, null));

        ResolvedModelTier resolved = fixtures.service.resolve(fixtures.user, defaultConversation(), fixtures.agent);

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }

    @Test
    @DisplayName("Hermes 가 기본 provider 만 주지 않으면 모델 이름으로 판정하고 provider 전체 숨김은 견주지 않는다")
    void judgesByModelNameWhenHermesGivesNoDefaultProvider() {
        HiddenFixtures sameModel = hiddenFixtures(hiding("example-provider", "example-model"));
        when(sameModel.options.profileDefaultOf(sameModel.agent))
                .thenReturn(ModelChoice.stored(null, "example-model", null));
        HiddenFixtures wholeProvider = hiddenFixtures(hiding("example-provider", null));
        when(wholeProvider.options.profileDefaultOf(wholeProvider.agent))
                .thenReturn(ModelChoice.stored(null, "example-model", null));

        assertThatThrownBy(() -> sameModel.service.resolve(sameModel.user, defaultConversation(), sameModel.agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
        assertThat(wholeProvider
                        .service
                        .resolve(wholeProvider.user, defaultConversation(), wholeProvider.agent)
                        .choice())
                .isEqualTo(ModelChoice.defaults());
    }

    @Test
    @DisplayName("mapping이 빈 단계도 profile 의 기본 모델이 숨긴 모델이면 MODEL_HIDDEN 으로 거절한다")
    void rejectsEmptyTierMappingWhenProfileDefaultModelIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        when(fixtures.definitions.findByGroupIdOrderByTier(10L)).thenReturn(fallbackDefinitions());
        when(fixtures.options.profileDefaultOf(fixtures.agent))
                .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        assertThatThrownBy(() -> fixtures.service.resolveTier(fixtures.user, ModelTier.FAST, fixtures.agent))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }

    @Test
    @DisplayName("대화 없는 실행이 보낼 값은 에이전트 기본값이고 그 값이 같은 숨김 판정을 지난다")
    void detachedChoiceIsAgentDefaultAndPassesSameHiddenCheck() {
        HiddenFixtures visible = hiddenFixtures(hiding("example-provider", "example-other"));
        agentDefault(visible.agent, "example-provider", "example-agent", "medium");
        HiddenFixtures hiddenDefault = hiddenFixtures(hiding("example-provider", "example-model"));
        when(hiddenDefault.options.profileDefaultOf(hiddenDefault.agent))
                .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        ModelChoice sent = visible.service.detachedChoice(visible.agent);
        ModelChoice empty = hiddenDefault.service.detachedChoice(hiddenDefault.agent);

        assertThat(sent).isEqualTo(ModelChoice.stored("example-provider", "example-agent", "medium"));
        assertThatCode(() -> visible.service.requireRunnable(visible.user, visible.agent, sent))
                .doesNotThrowAnyException();
        assertThat(empty.usesDefaultModel()).as("기본값이 빈 에이전트는 모델을 싣지 않는다").isTrue();
        assertThatThrownBy(() -> hiddenDefault.service.requireRunnable(hiddenDefault.user, hiddenDefault.agent, empty))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }

    /** 숨김이 없는 그룹의 대역이다. 실행 직전 판정이 숨김 목록을 먼저 읽는다. */
    private static ModelVisibilityService nothingHidden() {
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        when(visibility.hiddenFor(any())).thenReturn(HiddenModels.none());
        return visibility;
    }

    private static HiddenModels hiding(String provider, String model) {
        return new HiddenModels(List.of(new HiddenModels.Entry(provider, model)));
    }

    private static Conversation defaultConversation() {
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.DEFAULT);
        return conversation;
    }

    /** 그룹 10 이 {@code hidden} 을 숨긴 상태의 서비스와 대역이다. 에이전트 기본 모델은 비어 있다. */
    private static HiddenFixtures hiddenFixtures(HiddenModels hidden) {
        ModelTierDefinitionRepository definitions = mock(ModelTierDefinitionRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        when(visibility.hiddenFor(10L)).thenReturn(hidden);
        return new HiddenFixtures(
                new ModelTierService(
                        definitions,
                        mock(ModelTierGroupSettingRepository.class),
                        mock(AppUserRepository.class),
                        options,
                        visibility),
                definitions,
                options,
                new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER),
                mock(Agent.class));
    }

    private record HiddenFixtures(
            ModelTierService service,
            ModelTierDefinitionRepository definitions,
            ModelOptionsService options,
            CurrentUser user,
            Agent agent) {}

    private static void agentDefault(Agent agent, String provider, String model, String effort) {
        when(agent.defaultModelProvider()).thenReturn(provider);
        when(agent.defaultModel()).thenReturn(model);
        when(agent.defaultReasoningEffort()).thenReturn(effort);
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
        when(options.optionsForAgent(10L, agent))
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
                new ModelTierService(definitions, settings, users, options, nothingHidden()),
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

    private static List<ModelTierDefinition> storedDefinitions() {
        return List.of(
                ModelTierDefinition.of(10L, ModelTier.FAST, null, "example-fast", "low"),
                ModelTierDefinition.of(10L, ModelTier.BALANCED, null, "example-balanced", "medium"),
                ModelTierDefinition.of(10L, ModelTier.DEEP, null, "example-deep", "high"));
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
