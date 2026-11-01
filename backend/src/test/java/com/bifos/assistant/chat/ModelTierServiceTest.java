package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.modelchoice.application.GroupModelCatalogService;
import com.bifos.assistant.chat.modelchoice.application.ModelOptionsService;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.modelchoice.application.ModelVisibilityService;
import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelCatalog;
import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelTiers;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelOptions;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelTierOptions;
import com.bifos.assistant.chat.modelchoice.application.dto.ResolvedModelTier;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 칸 정의 저장의 관리자 경계와 실행 칸 우선순위를 확인한다. */
class ModelTierServiceTest {

    private static final CurrentUser MEMBER = new CurrentUser(1L, "member@example.com", "사용자", 10L, UserRole.MEMBER);

    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "관리자", 10L, UserRole.ADMIN);


    @Test
    @DisplayName("선택 없는 대화는 사용자와 그룹 기본 칸이 모두 없으면 요청에 모델을 싣지 않는다")
    void inheritedConversationWithoutAnyDefaultTierSendsNoModel() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(store, users, options, nothingHidden(), mock(GroupModelCatalogService.class));
        when(users.findById(1L)).thenReturn(Optional.empty());

        ResolvedModelTier resolved = service.resolve(MEMBER, mock(Conversation.class), mock(Agent.class));

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        assertThat(resolved.tier()).isNull();
        verifyNoInteractions(options);
    }


    @Test
    @DisplayName("설정 줄이 없는 새 그룹은 저장하지 않고 고를 칸을 싣지 않는다")
    void newGroupGetsNoTiersWithoutSaving() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(store, users, options, nothingHidden(), mock(GroupModelCatalogService.class));
        when(users.findById(1L)).thenReturn(Optional.empty());

        ModelTierOptions result = service.optionsFor(MEMBER, mock(Agent.class));

        assertThat(result.tiers()).isEmpty();
        assertThat(result.groupDefaultTier()).isNull();
        assertThat(store.saves()).isZero();
        verifyNoInteractions(options);
    }


    @Test
    @DisplayName("MEMBER 역할이 받는 칸에는 provider 와 모델과 effort 가 비고 칸과 이름과 기본값은 남는다")
    void memberGetsTiersWithoutProviderModelOrEffort() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.MEDIUM);
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());

        ModelTierOptions result = fixtures.service().optionsFor(fixtures.user(), fixtures.agent());

        assertThat(result.tiers())
            .extracting(ModelTierOptions.Tier::tier, ModelTierOptions.Tier::label)
            .containsExactly(
                tuple(ModelTier.INSTANT, "Instant"),
                tuple(ModelTier.MEDIUM, "Medium"),
                tuple(ModelTier.HIGH, "High"),
                tuple(ModelTier.EXTRA_HIGH, "Extra High"),
                tuple(ModelTier.PRO, "Pro"));
        assertThat(result.tiers()).allSatisfy(tier -> {
            assertThat(tier.provider()).as("provider").isNull();
            assertThat(tier.model()).as("model").isNull();
            assertThat(tier.reasoningEffort()).as("reasoningEffort").isNull();
        });
        assertThat(result.groupDefaultTier()).isEqualTo(ModelTier.MEDIUM);
        assertThat(result.admin()).isFalse();
        // 싣지 않을 provider 를 알아내려고 Hermes 목록을 읽지 않는다.
        verifyNoInteractions(fixtures.options());
    }


    @Test
    @DisplayName("ADMIN 역할이 받는 칸에는 provider 와 모델과 effort 가 그대로 실린다")
    void adminGetsTiersWithProviderModelAndEffort() {
        TierFixtures fixtures = tierFixtures();
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());

        ModelTierOptions result = fixtures.service().optionsFor(ADMIN, fixtures.agent());

        assertThat(result.tiers())
            .extracting(
                ModelTierOptions.Tier::tier,
                ModelTierOptions.Tier::provider,
                ModelTierOptions.Tier::model,
                ModelTierOptions.Tier::reasoningEffort)
            .containsExactly(
                tuple(ModelTier.INSTANT, "openai-codex", "example-fast", "low"),
                tuple(ModelTier.MEDIUM, "openai-codex", "example-balanced", "medium"),
                tuple(ModelTier.HIGH, "openai-codex", "example-deep", "high"),
                tuple(ModelTier.EXTRA_HIGH, "openai-codex", "example-deep", "xhigh"),
                tuple(ModelTier.PRO, "openai-codex", "example-pro", "max"));
        assertThat(result.admin()).isTrue();
    }


    @Test
    @DisplayName("optionsFor 는 빈 칸을 싣지 않고 채운 칸만 차례대로 싣는다")
    void optionsForSkipsEmptyStops() {
        FakeModelSettingStore store = new FakeModelSettingStore()
            .tiers(
                new ModelSetting.Stop(ModelTier.INSTANT, "openai-codex", "example-fast", "low"),
                new ModelSetting.Stop(ModelTier.HIGH, "openai-codex", "example-deep", "high"));
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(store, users, options, nothingHidden(), mock(GroupModelCatalogService.class));
        when(users.findById(1L)).thenReturn(Optional.empty());

        ModelTierOptions member = service.optionsFor(MEMBER, mock(Agent.class));
        ModelTierOptions admin = service.optionsFor(ADMIN, mock(Agent.class));

        assertThat(member.tiers())
            .extracting(ModelTierOptions.Tier::tier)
            .containsExactly(ModelTier.INSTANT, ModelTier.HIGH);
        assertThat(admin.tiers())
            .extracting(ModelTierOptions.Tier::tier, ModelTierOptions.Tier::model)
            .containsExactly(tuple(ModelTier.INSTANT, "example-fast"), tuple(ModelTier.HIGH, "example-deep"));
        // provider 를 비운 칸이 없으니 에이전트의 기본 provider 를 읽지 않는다.
        verifyNoInteractions(options);
    }


    @Test
    @DisplayName("관리자는 에이전트 없이 그룹 칸을 읽고 provider 를 비운 칸은 비운 채 받는다")
    void adminReadsGroupTiersWithoutAgentAndBlankProviderStaysBlank() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.MEDIUM);

        GroupModelTiers result = fixtures.service().groupTiers(ADMIN);

        assertThat(result.tiers())
            .extracting(
                ModelTierOptions.Tier::tier,
                ModelTierOptions.Tier::provider,
                ModelTierOptions.Tier::model,
                ModelTierOptions.Tier::reasoningEffort)
            .containsExactly(
                tuple(ModelTier.INSTANT, null, "example-fast", "low"),
                tuple(ModelTier.MEDIUM, null, "example-balanced", "medium"),
                tuple(ModelTier.HIGH, null, "example-deep", "high"),
                tuple(ModelTier.EXTRA_HIGH, null, "example-deep", "xhigh"),
                tuple(ModelTier.PRO, null, "example-pro", "max"));
        assertThat(result.groupDefaultTier()).isEqualTo(ModelTier.MEDIUM);
        // 에이전트의 목록을 읽지 않는다. 관리자가 시작할 수 없는 에이전트만 있어도 그룹 칸을 읽는다.
        verifyNoInteractions(fixtures.options());
    }


    @Test
    @DisplayName("MEMBER 역할은 그룹 칸의 관리자 조회를 받지 못한다")
    void memberCannotReadGroupTiers() {
        TierFixtures fixtures = tierFixtures();

        assertThatThrownBy(() -> fixtures.service().groupTiers(fixtures.user()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.FORBIDDEN);
    }


    @Test
    @DisplayName("관리자가 아니면 그룹 칸 정의를 바꾸지 못한다")
    void memberCannotSaveGroupModelTiers() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        ModelTierService service = service(store, users, options, nothingHidden(), catalog);

        assertThatThrownBy(() -> service.saveGroup(MEMBER, List.of(), null))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(store.reads()).isZero();
        assertThat(store.saves()).isZero();
        verifyNoInteractions(users, options, catalog);
    }


    @Test
    @DisplayName("다섯 칸이 모두 없으면 관리자도 그룹 칸을 저장하지 못한다")
    void rejectsIncompleteDefinitions() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);
        List<ModelTierOptions.Tier> one = List.of(tier(ModelTier.INSTANT, null, "example-fast", "low"));
        List<ModelTierOptions.Tier> three = List.of(
            tier(ModelTier.INSTANT, null, null, null),
            tier(ModelTier.MEDIUM, null, null, null),
            tier(ModelTier.HIGH, null, null, null));

        assertThatThrownBy(() -> service.saveGroup(ADMIN, one, ModelTier.INSTANT))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.saveGroup(ADMIN, three, ModelTier.INSTANT))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(store.saves()).isZero();
        verifyNoInteractions(catalog);
    }


    @Test
    @DisplayName("다섯 칸을 모두 받으면 저장하고 그룹 기본 칸도 저장된다")
    void savesAllFiveStopsAndGroupDefault() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        when(catalog.catalogFor(10L)).thenReturn(groupCatalog(
            "openai-codex", "example-fast", "example-balanced", "example-deep", "example-pro"));
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);

        service.saveGroup(
            ADMIN,
            List.of(
                tier(ModelTier.INSTANT, "openai-codex", "example-fast", "low"),
                tier(ModelTier.MEDIUM, "openai-codex", "example-balanced", "medium"),
                tier(ModelTier.HIGH, "openai-codex", "example-deep", "high"),
                tier(ModelTier.EXTRA_HIGH, "openai-codex", "example-deep", "xhigh"),
                tier(ModelTier.PRO, "openai-codex", "example-pro", "max")),
            ModelTier.HIGH);

        assertThat(store.saves()).isEqualTo(1);
        assertThat(store.current().tiers())
            .extracting(
                ModelSetting.Stop::tier,
                ModelSetting.Stop::provider,
                ModelSetting.Stop::model,
                ModelSetting.Stop::reasoningEffort)
            .containsExactly(
                tuple(ModelTier.INSTANT, "openai-codex", "example-fast", "low"),
                tuple(ModelTier.MEDIUM, "openai-codex", "example-balanced", "medium"),
                tuple(ModelTier.HIGH, "openai-codex", "example-deep", "high"),
                tuple(ModelTier.EXTRA_HIGH, "openai-codex", "example-deep", "xhigh"),
                tuple(ModelTier.PRO, "openai-codex", "example-pro", "max"));
        assertThat(store.current().defaultTier()).isEqualTo(ModelTier.HIGH);
    }


    @Test
    @DisplayName("바뀐 칸만 그룹 목록과 견주고 provider 와 모델이 그대로인 칸은 effort 가 바뀌어도 견주지 않는다")
    void comparesOnlyChangedStopsWithGroupCatalog() {
        FakeModelSettingStore store = new FakeModelSettingStore().tiers(explicitStops());
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        // 바뀐 칸의 모델만 있는 목록이다. 바뀌지 않은 칸까지 견주면 다시 읽어도 없어 거절된다.
        when(catalog.catalogFor(10L)).thenReturn(groupCatalog("openai-codex", "example-other"));
        when(catalog.refreshedCatalogFor(10L)).thenReturn(groupCatalog("openai-codex", "example-other"));
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);

        service.saveGroup(
            ADMIN,
            List.of(
                tier(ModelTier.INSTANT, "openai-codex", "example-fast", "low"),
                tier(ModelTier.MEDIUM, "openai-codex", "example-balanced", "high"),
                tier(ModelTier.HIGH, "openai-codex", "example-other", "high"),
                tier(ModelTier.EXTRA_HIGH, null, null, null),
                tier(ModelTier.PRO, "openai-codex", "example-pro", "max")),
            null);

        assertThat(store.saves()).isEqualTo(1);
        assertThat(store.current().stop(ModelTier.MEDIUM).reasoningEffort()).isEqualTo("high");
        assertThat(store.current().stop(ModelTier.HIGH).model()).isEqualTo("example-other");
        assertThat(store.current().stop(ModelTier.EXTRA_HIGH).isEmpty()).isTrue();
        verify(catalog).catalogFor(10L);
        verify(catalog, never()).refreshedCatalogFor(any());
    }


    @Test
    @DisplayName("그룹 목록을 읽지 못해도 바뀐 칸이 없으면 저장되고 바뀐 칸이 있으면 그 오류가 그대로 나간다")
    void savesWithoutCatalogWhenNothingChanged() {
        FakeModelSettingStore store = new FakeModelSettingStore().tiers(explicitStops());
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        when(catalog.catalogFor(10L)).thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);

        // effort 만 고치고 칸 하나를 비운다.
        service.saveGroup(
            ADMIN,
            List.of(
                tier(ModelTier.INSTANT, "openai-codex", "example-fast", "medium"),
                tier(ModelTier.MEDIUM, "openai-codex", "example-balanced", "medium"),
                tier(ModelTier.HIGH, "openai-codex", "example-deep", "high"),
                tier(ModelTier.EXTRA_HIGH, "openai-codex", "example-deep", "xhigh"),
                tier(ModelTier.PRO, null, null, null)),
            ModelTier.INSTANT);

        assertThat(store.saves()).isEqualTo(1);
        assertThat(store.current().stop(ModelTier.INSTANT).reasoningEffort()).isEqualTo("medium");
        assertThat(store.current().stop(ModelTier.PRO).isEmpty()).isTrue();
        verifyNoInteractions(catalog);

        assertThatThrownBy(() -> service.saveGroup(
            ADMIN,
            List.of(
                tier(ModelTier.INSTANT, "openai-codex", "example-other", "medium"),
                tier(ModelTier.MEDIUM, "openai-codex", "example-balanced", "medium"),
                tier(ModelTier.HIGH, "openai-codex", "example-deep", "high"),
                tier(ModelTier.EXTRA_HIGH, "openai-codex", "example-deep", "xhigh"),
                tier(ModelTier.PRO, null, null, null)),
            ModelTier.INSTANT))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
        assertThat(store.saves()).isEqualTo(1);
    }


    @Test
    @DisplayName("들고 있던 그룹 목록에 없으면 다시 읽어 있으면 저장하고 다시 읽어도 없으면 VALIDATION_FAILED 로 거절한다")
    void rereadsGroupCatalogBeforeRejecting() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        when(catalog.catalogFor(10L)).thenReturn(groupCatalog("openai-codex", "another-model"));
        when(catalog.refreshedCatalogFor(10L))
            .thenReturn(groupCatalog("openai-codex", "example-new"))
            .thenReturn(groupCatalog("openai-codex", "another-model"));
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);

        service.saveGroup(ADMIN, fiveTiers(tier(ModelTier.INSTANT, "openai-codex", "example-new", "low")), null);

        assertThat(store.saves()).isEqualTo(1);
        assertThat(store.current().stop(ModelTier.INSTANT).model()).isEqualTo("example-new");

        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, "openai-codex", "example-missing", "low")), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(store.saves()).isEqualTo(1);
        assertThat(store.current().stop(ModelTier.INSTANT).model()).isEqualTo("example-new");
    }


    @Test
    @DisplayName("바뀐 칸이 provider 를 비우면 VALIDATION_FAILED 이고 옮겨 온 정의의 빈 provider 를 그대로 두는 저장은 된다")
    void changedStopMustNameProviderButMigratedBlankProviderStays() {
        FakeModelSettingStore store = new FakeModelSettingStore()
            .tiers(new ModelSetting.Stop(ModelTier.INSTANT, null, "example-fast", "low"));
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);

        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, null, "example-other", "low")), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(store.saves()).isZero();

        service.saveGroup(ADMIN, fiveTiers(tier(ModelTier.INSTANT, null, "example-fast", "high")), null);

        assertThat(store.saves()).isEqualTo(1);
        assertThat(store.current().stop(ModelTier.INSTANT))
            .isEqualTo(new ModelSetting.Stop(ModelTier.INSTANT, null, "example-fast", "high"));
        verifyNoInteractions(catalog);
    }


    @Test
    @DisplayName("그룹 칸 저장은 provider와 model의 앞뒤 공백을 없애고 빈 provider는 null로 둔다")
    void saveGroupNormalizesProviderAndModelBeforePersisting() {
        // 옮겨 온 정의처럼 provider 를 비운 INSTANT 칸이 이미 있다. 공백 provider 는 그 칸을 바꾸지 않는다.
        FakeModelSettingStore store = new FakeModelSettingStore()
            .tiers(new ModelSetting.Stop(ModelTier.INSTANT, null, "example-fast", "low"));
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        when(catalog.catalogFor(10L)).thenReturn(groupCatalog("openai-codex", "example-balanced", "example-deep"));
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), nothingHidden(), catalog);

        service.saveGroup(
            ADMIN,
            List.of(
                tier(ModelTier.INSTANT, "   ", " example-fast ", "low"),
                tier(ModelTier.MEDIUM, " openai-codex ", " example-balanced ", "medium"),
                tier(ModelTier.HIGH, " openai-codex ", " example-deep ", "high"),
                tier(ModelTier.EXTRA_HIGH, null, null, null),
                tier(ModelTier.PRO, null, null, null)),
            ModelTier.INSTANT);

        assertThat(store.current().tiers())
            .extracting(ModelSetting.Stop::provider, ModelSetting.Stop::model)
            .containsExactly(
                tuple(null, "example-fast"),
                tuple("openai-codex", "example-balanced"),
                tuple("openai-codex", "example-deep"),
                tuple(null, null),
                tuple(null, null));
    }


    @Test
    @DisplayName("관리자는 provider만 또는 effort만 있는 부분 칸 mapping을 저장하지 못한다")
    void rejectsPartialTierMappings() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        ModelTierService service = service(
            store,
            mock(AppUserRepository.class),
            mock(ModelOptionsService.class),
            nothingHidden(),
            mock(GroupModelCatalogService.class));

        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, "openai-codex", null, null)), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, null, "example-fast", null)), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, null, null, "low")), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(store.saves()).isZero();
    }


    @Test
    @DisplayName("그룹 칸 정의는 effort none 을 받지 않는다")
    void rejectsNoneEffortInGroupTierDefinition() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        ModelTierService service = service(
            store,
            mock(AppUserRepository.class),
            mock(ModelOptionsService.class),
            nothingHidden(),
            mock(GroupModelCatalogService.class));

        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, "openai-codex", "example-fast", "none")), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(store.saves()).isZero();
    }


    @Test
    @DisplayName("고른 칸이 비어 있으면 칸 기록을 남기고 요청에 모델을 싣지 않는다")
    void emptyTierMappingFallsBackToProfileDefault() {
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(
            new FakeModelSettingStore(),
            mock(AppUserRepository.class),
            options,
            nothingHidden(),
            mock(GroupModelCatalogService.class));

        ResolvedModelTier resolved = service.resolveTier(MEMBER, ModelTier.INSTANT, mock(Agent.class));

        assertThat(resolved.tier()).isEqualTo(ModelTier.INSTANT);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        verifyNoInteractions(options);
    }


    @Test
    @DisplayName("provider를 비운 칸은 요청 profile의 catalog에서 따로 검증하고 그 profile의 provider를 쓴다")
    void storedTiersUseEachProfilesCatalog() {
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(
            new FakeModelSettingStore().tiers(storedStops()),
            mock(AppUserRepository.class),
            options,
            nothingHidden(),
            mock(GroupModelCatalogService.class));
        Agent fastAgent = mock(Agent.class);
        Agent balancedAgent = mock(Agent.class);
        Agent deepAgent = mock(Agent.class);
        when(options.optionsForAgent(10L, fastAgent)).thenReturn(catalog("fast-provider", "example-fast"));
        when(options.optionsForAgent(10L, balancedAgent)).thenReturn(catalog("balanced-provider", "example-balanced"));
        when(options.optionsForAgent(10L, deepAgent)).thenReturn(catalog("deep-provider", "example-deep"));

        ResolvedModelTier fast = service.resolveTier(MEMBER, ModelTier.INSTANT, fastAgent);
        ResolvedModelTier balanced = service.resolveTier(MEMBER, ModelTier.MEDIUM, balancedAgent);
        ResolvedModelTier deep = service.resolveTier(MEMBER, ModelTier.HIGH, deepAgent);

        assertThat(fast.choice()).isEqualTo(ModelChoice.of("fast-provider", "example-fast", "low"));
        assertThat(balanced.choice()).isEqualTo(ModelChoice.of("balanced-provider", "example-balanced", "medium"));
        assertThat(deep.choice()).isEqualTo(ModelChoice.of("deep-provider", "example-deep", "high"));
    }


    @Test
    @DisplayName("칸 모델이 요청 profile의 catalog에 없으면 실행 전에 거절한다")
    void rejectsStoredTierMissingFromAgentCatalog() {
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(
            new FakeModelSettingStore().tiers(storedStops()),
            mock(AppUserRepository.class),
            options,
            nothingHidden(),
            mock(GroupModelCatalogService.class));
        Agent agent = mock(Agent.class);
        when(options.optionsForAgent(10L, agent)).thenReturn(catalog("fast-provider", "another-model"));
        when(options.refreshedOptionsForAgent(10L, agent)).thenReturn(catalog("fast-provider", "another-model"));

        assertThatThrownBy(() -> service.resolveTier(MEMBER, ModelTier.INSTANT, agent))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(options).refreshedOptionsForAgent(10L, agent);
    }


    @Test
    @DisplayName("칸을 고를 때 들고 있던 목록에 모델이 없어도 다시 읽은 목록에 있으면 받는다")
    void acceptsTierWhenRereadCatalogOffersModel() {
        TierFixtures fixtures = tierFixtures();
        when(fixtures.options().optionsForAgent(10L, fixtures.agent())).thenReturn(substituteCatalog());
        when(fixtures.options().refreshedOptionsForAgent(10L, fixtures.agent())).thenReturn(fullCatalog());

        ResolvedModelTier deep = fixtures.service().resolveTier(fixtures.user(), ModelTier.HIGH, fixtures.agent());

        assertThat(deep.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-deep", "high"));
        verify(fixtures.options()).refreshedOptionsForAgent(10L, fixtures.agent());
    }


    @Test
    @DisplayName("실행할 때 들고 있던 목록이 대체 목록이어도 다시 읽은 목록으로 칸을 해석한다")
    void runResolvesTierWithRereadCatalogAfterSubstituteCatalog() {
        TierFixtures fixtures = tierFixtures();
        when(fixtures.options().optionsForAgent(10L, fixtures.agent())).thenReturn(substituteCatalog());
        when(fixtures.options().refreshedOptionsForAgent(10L, fixtures.agent())).thenReturn(fullCatalog());

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), tierConversation(ModelTier.HIGH), fixtures.agent());

        assertThat(resolved.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-deep", "high"));
        assertThat(resolved.tier()).isEqualTo(ModelTier.HIGH);
    }


    @Test
    @DisplayName("실행할 때 다시 읽어도 목록에 없으면 막지 않고 그 모델로 보낸다")
    void runSendsTierModelEvenWhenCatalogStillLacksIt() {
        TierFixtures fixtures = tierFixtures();
        when(fixtures.options().optionsForAgent(10L, fixtures.agent())).thenReturn(substituteCatalog());
        when(fixtures.options().refreshedOptionsForAgent(10L, fixtures.agent())).thenReturn(substituteCatalog());

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), tierConversation(ModelTier.HIGH), fixtures.agent());

        assertThat(resolved.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-deep", "high"));
        verify(fixtures.options()).refreshedOptionsForAgent(10L, fixtures.agent());
    }


    @Test
    @DisplayName("목록에 있는 모델은 실행할 때 다시 읽지 않는다")
    void runDoesNotRereadWhenCatalogOffersModel() {
        TierFixtures fixtures = tierFixtures();

        fixtures.service().resolve(fixtures.user(), tierConversation(ModelTier.HIGH), fixtures.agent());

        verify(fixtures.options(), never()).refreshedOptionsForAgent(any(), any());
    }


    @Test
    @DisplayName("숨긴 칸 모델은 목록을 다시 읽지 않고 실행과 칸 고르기 모두 MODEL_HIDDEN 으로 거절한다")
    void hiddenTierModelIsRejectedWithoutRereadingOnRunAndOnChoice() {
        TierFixtures fixtures = tierFixtures();
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        when(visibility.hiddenFor(any())).thenReturn(HiddenModels.none());
        doThrow(new ApiException(ErrorCode.MODEL_HIDDEN, "hidden"))
            .when(visibility)
            .requireVisible(any(), any());
        ModelTierService service = service(
            fixtures.store(), fixtures.users(), fixtures.options(), visibility, mock(GroupModelCatalogService.class));
        when(fixtures.options().optionsForAgent(10L, fixtures.agent())).thenReturn(substituteCatalog());

        assertThatThrownBy(() -> service.resolveTier(fixtures.user(), ModelTier.HIGH, fixtures.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
        assertThatThrownBy(() -> service.resolve(fixtures.user(), tierConversation(ModelTier.HIGH), fixtures.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
        verify(fixtures.options(), never()).refreshedOptionsForAgent(any(), any());
    }


    @Test
    @DisplayName("모든 칸이 빈 그룹은 관리자에게도 catalog 조회 없이 고를 칸을 싣지 않는다")
    void storedFallbackTiersDoNotReadCatalogForOptions() {
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelTierService service = service(
            new FakeModelSettingStore().tiers(fallbackStops()),
            users,
            options,
            nothingHidden(),
            mock(GroupModelCatalogService.class));
        when(users.findById(1L)).thenReturn(Optional.empty());

        ModelTierOptions result = service.optionsFor(ADMIN, mock(Agent.class));

        assertThat(result.tiers()).isEmpty();
        verifyNoInteractions(options);
    }


    @Test
    @DisplayName("상속 대화는 사용자 기본 칸을 그룹 기본 칸보다 먼저 쓴다")
    void inheritedConversationPrefersUserDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.HIGH);
        AppUser appUser = mock(AppUser.class);
        when(appUser.modelDefaultTier()).thenReturn("INSTANT");
        when(fixtures.users().findById(1L)).thenReturn(Optional.of(appUser));
        Conversation conversation = mock(Conversation.class);

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), conversation, fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.INSTANT);
        assertThat(resolved.choice().model()).isEqualTo("example-fast");
        assertThat(resolved.choice().reasoningEffort()).isEqualTo("low");
    }


    @Test
    @DisplayName("사용자 기본 칸이 없으면 상속 대화는 그룹 기본 칸을 쓴다")
    void inheritedConversationFallsBackToGroupDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.HIGH);
        AppUser appUser = mock(AppUser.class);
        when(appUser.modelDefaultTier()).thenReturn(null);
        when(fixtures.users().findById(1L)).thenReturn(Optional.of(appUser));
        Conversation conversation = mock(Conversation.class);

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), conversation, fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.HIGH);
        assertThat(resolved.choice().model()).isEqualTo("example-deep");
        assertThat(resolved.choice().reasoningEffort()).isEqualTo("high");
    }


    @Test
    @DisplayName("명시 칸은 사용자와 그룹 기본 칸보다 먼저 쓴다")
    void tierConversationOverridesUserAndGroupDefaults() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.HIGH);
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.TIER);
        when(conversation.modelTier()).thenReturn(ModelTier.MEDIUM);

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), conversation, fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.MEDIUM);
        assertThat(resolved.choice().reasoningEffort()).isEqualTo("medium");
        // 설정은 고른 칸의 정의를 읽을 때 한 번만 읽는다. 그룹 기본 칸을 찾으려 다시 읽지 않는다.
        assertThat(fixtures.store().reads()).isEqualTo(1);
        verifyNoInteractions(fixtures.users());
    }


    @Test
    @DisplayName("실행에 넘길 칸 선택은 뒤의 칸 정의 변경에 영향받지 않는다")
    void resolvedTierChoiceIsImmutableSnapshot() {
        TierFixtures fixtures = tierFixtures();

        ResolvedModelTier resolved = fixtures.service().resolveTier(fixtures.user(), ModelTier.INSTANT, fixtures.agent());
        fixtures.store().tiers(new ModelSetting.Stop(ModelTier.INSTANT, "openai-codex", "example-deep", "high"));

        ResolvedModelTier updated = fixtures.service().resolveTier(fixtures.user(), ModelTier.INSTANT, fixtures.agent());

        assertThat(resolved.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-fast", "low"));
        assertThat(updated.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-deep", "high"));
    }


    @Test
    @DisplayName("선택 없는 대화는 그룹 기본 칸이 비어 있으면 칸 기록을 남기고 모델을 싣지 않는다")
    void inheritedConversationWithEmptyGroupDefaultTierSendsNoModel() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().tiers(fallbackStops()).defaultTier(ModelTier.HIGH);
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.HIGH);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }


    @Test
    @DisplayName("mapping이 빈 칸은 칸 기록을 남기고 모델을 싣지 않는다")
    void emptyTierMappingSendsNoModel() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().tiers(fallbackStops());

        ResolvedModelTier resolved = fixtures.service().resolveTier(fixtures.user(), ModelTier.HIGH, fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.HIGH);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }


    @Test
    @DisplayName("해석한 모델이 숨긴 모델이면 다른 모델로 바꾸지 않고 MODEL_HIDDEN 으로 거절한다")
    void hiddenModelIsRejectedInsteadOfReplaced() {
        FakeModelSettingStore store = new FakeModelSettingStore()
            .tiers(new ModelSetting.Stop(ModelTier.HIGH, "openai-codex", "hidden-model", "high"))
            .hidden(hiding("openai-codex", "hidden-model"));
        ModelOptionsService options = mock(ModelOptionsService.class);
        Agent agent = mock(Agent.class);
        when(options.optionsForAgent(10L, agent)).thenReturn(catalog("openai-codex", "hidden-model"));
        ModelTierService service = service(
            store,
            mock(AppUserRepository.class),
            options,
            new ModelVisibilityService(store),
            mock(GroupModelCatalogService.class));

        assertThatThrownBy(() -> service.resolve(MEMBER, tierConversation(ModelTier.HIGH), agent))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }


    @Test
    @DisplayName("숨긴 provider를 명시한 칸 정의는 저장하지 못한다")
    void hiddenProviderCannotBeSavedAsTierDefinition() {
        FakeModelSettingStore store = new FakeModelSettingStore();
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        GroupModelCatalogService catalog = mock(GroupModelCatalogService.class);
        ModelTierService service = service(
            store, mock(AppUserRepository.class), mock(ModelOptionsService.class), visibility, catalog);
        doThrow(new ApiException(ErrorCode.MODEL_HIDDEN, "hidden"))
            .when(visibility)
            .requireVisible(10L, ModelChoice.stored("hidden-provider", "example-fast", null));

        assertThatThrownBy(() -> service.saveGroup(
            ADMIN, fiveTiers(tier(ModelTier.INSTANT, "hidden-provider", "example-fast", "low")), null))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException)error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
        assertThat(store.saves()).isZero();
        verifyNoInteractions(catalog);
    }


    @Test
    @DisplayName("숨김이 없으면 모델을 싣지 않는 해석은 목록을 읽지 않고 통과한다")
    void resolvesDefaultWithoutReadingCatalogWhenNothingIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(HiddenModels.none());

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent());

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        verifyNoInteractions(fixtures.options());
    }


    @Test
    @DisplayName("선택 없는 실행은 profile 의 기본 모델이 숨긴 모델이면 MODEL_HIDDEN 으로 거절한다")
    void rejectsDefaultRunWhenProfileDefaultModelIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        when(fixtures.options().profileDefaultOf(fixtures.agent()))
            .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        assertThatThrownBy(() -> fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }


    @Test
    @DisplayName("선택 없는 실행은 profile 의 기본 provider 전체를 숨겼으면 MODEL_HIDDEN 으로 거절한다")
    void rejectsDefaultRunWhenProfileDefaultProviderIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", null));
        when(fixtures.options().profileDefaultOf(fixtures.agent()))
            .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        assertThatThrownBy(() -> fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }


    @Test
    @DisplayName("숨김이 있어도 profile 의 기본 모델이 숨기지 않은 모델이면 세 값을 비운 채 통과한다")
    void passesDefaultRunWhenProfileDefaultModelIsNotHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-other"));
        when(fixtures.options().profileDefaultOf(fixtures.agent()))
            .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent());

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }


    @Test
    @DisplayName("숨김이 있는데 목록을 한 번도 읽지 못했으면 통과시키지 않고 HERMES_UNAVAILABLE 로 거절한다")
    void rejectsDefaultRunWhenCatalogWasNeverRead() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        when(fixtures.options().profileDefaultOf(fixtures.agent()))
            .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.HERMES_UNAVAILABLE);
    }


    @Test
    @DisplayName("Hermes 가 profile 의 기본 모델을 주지 않으면 판정할 값이 없어 통과한다")
    void passesDefaultRunWhenHermesGivesNoDefaultModel() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", null));
        when(fixtures.options().profileDefaultOf(fixtures.agent()))
            .thenReturn(ModelChoice.stored("example-provider", null, null));

        ResolvedModelTier resolved = fixtures.service().resolve(fixtures.user(), mock(Conversation.class), fixtures.agent());

        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }


    @Test
    @DisplayName("Hermes 가 기본 provider 만 주지 않으면 모델 이름으로 판정하고 provider 전체 숨김은 견주지 않는다")
    void judgesByModelNameWhenHermesGivesNoDefaultProvider() {
        HiddenFixtures sameModel = hiddenFixtures(hiding("example-provider", "example-model"));
        when(sameModel.options().profileDefaultOf(sameModel.agent()))
            .thenReturn(ModelChoice.stored(null, "example-model", null));
        HiddenFixtures wholeProvider = hiddenFixtures(hiding("example-provider", null));
        when(wholeProvider.options().profileDefaultOf(wholeProvider.agent()))
            .thenReturn(ModelChoice.stored(null, "example-model", null));

        assertThatThrownBy(() -> sameModel.service().resolve(sameModel.user(), mock(Conversation.class), sameModel.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
        assertThat(wholeProvider.service()
            .resolve(wholeProvider.user(), mock(Conversation.class), wholeProvider.agent())
            .choice())
                .isEqualTo(ModelChoice.defaults());
    }


    @Test
    @DisplayName("mapping이 빈 칸도 profile 의 기본 모델이 숨긴 모델이면 MODEL_HIDDEN 으로 거절한다")
    void rejectsEmptyTierMappingWhenProfileDefaultModelIsHidden() {
        HiddenFixtures fixtures = hiddenFixtures(hiding("example-provider", "example-model"));
        when(fixtures.options().profileDefaultOf(fixtures.agent()))
            .thenReturn(ModelChoice.stored("example-provider", "example-model", null));

        assertThatThrownBy(() -> fixtures.service().resolveTier(fixtures.user(), ModelTier.INSTANT, fixtures.agent()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }


    @Test
    @DisplayName("대화 없는 실행은 사용자 기본 칸을 그룹 기본 칸보다 먼저 쓴다")
    void detachedChoicePrefersUserDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.HIGH);
        AppUser appUser = mock(AppUser.class);
        when(appUser.modelDefaultTier()).thenReturn("INSTANT");
        when(fixtures.users().findById(1L)).thenReturn(Optional.of(appUser));

        ResolvedModelTier resolved = fixtures.service().detachedChoice(fixtures.user(), fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.INSTANT);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-fast", "low"));
    }


    @Test
    @DisplayName("대화 없는 실행은 사용자 기본 칸이 없으면 그룹 기본 칸을 쓴다")
    void detachedChoiceFallsBackToGroupDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().defaultTier(ModelTier.HIGH);
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());

        ResolvedModelTier resolved = fixtures.service().detachedChoice(fixtures.user(), fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.HIGH);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.of("openai-codex", "example-deep", "high"));
    }


    @Test
    @DisplayName("대화 없는 실행은 사용자와 그룹 기본 칸이 모두 없으면 요청에 모델을 싣지 않는다")
    void detachedChoiceSendsNoModelWithoutAnyDefaultTier() {
        TierFixtures fixtures = tierFixtures();
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());

        ResolvedModelTier resolved = fixtures.service().detachedChoice(fixtures.user(), fixtures.agent());

        assertThat(resolved.tier()).isNull();
        assertThat(resolved.choice().usesDefaultModel()).isTrue();
        assertThat(resolved.choice().reasoningEffort()).isNull();
    }


    @Test
    @DisplayName("대화 없는 실행은 고른 칸이 비어 있으면 칸 기록을 남기고 모델을 싣지 않는다")
    void detachedChoiceSendsNoModelWhenDefaultTierIsEmpty() {
        TierFixtures fixtures = tierFixtures();
        fixtures.store().tiers(fallbackStops()).defaultTier(ModelTier.HIGH);
        when(fixtures.users().findById(1L)).thenReturn(Optional.empty());

        ResolvedModelTier resolved = fixtures.service().detachedChoice(fixtures.user(), fixtures.agent());

        assertThat(resolved.tier()).isEqualTo(ModelTier.HIGH);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
    }


    @Test
    @DisplayName("대화 없는 실행의 값은 숨긴 모델이어도 해석에서 거절하지 않고 requireRunnable 이 MODEL_HIDDEN 으로 거절한다")
    void detachedChoiceDefersHiddenCheckToRequireRunnable() {
        FakeModelSettingStore store = new FakeModelSettingStore().tiers(storedStops());
        store.defaultTier(ModelTier.INSTANT);
        ModelOptionsService options = mock(ModelOptionsService.class);
        Agent agent = mock(Agent.class);
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        when(visibility.hiddenFor(10L)).thenReturn(hiding("openai-codex", "example-fast"));
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findById(1L)).thenReturn(Optional.empty());
        ModelTierService service = service(store, users, options, visibility, mock(GroupModelCatalogService.class));
        when(options.optionsForAgent(10L, agent)).thenReturn(fullCatalog());

        ResolvedModelTier resolved = service.detachedChoice(MEMBER, agent);

        assertThat(resolved.choice().model()).isEqualTo("example-fast");
        verify(options, never()).refreshedOptionsForAgent(any(), any());
        assertThatThrownBy(() -> service.requireRunnable(MEMBER, agent, resolved.choice()))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }


    private static ModelTierService service(
        FakeModelSettingStore store,
        AppUserRepository users,
        ModelOptionsService options,
        ModelVisibilityService visibility,
        GroupModelCatalogService catalog) {
        return new ModelTierService(store, users, options, visibility, catalog, Clock.systemUTC());
    }


    private static Conversation tierConversation(ModelTier tier) {
        Conversation conversation = mock(Conversation.class);
        when(conversation.modelSelectionMode()).thenReturn(ModelSelectionMode.TIER);
        when(conversation.modelTier()).thenReturn(tier);
        return conversation;
    }


    /** Hermes 가 Codex 목록을 읽지 못해 돌려주는 대체 목록처럼 칸 모델이 빠진 목록이다. */
    private static ModelOptions substituteCatalog() {
        return catalog("openai-codex", "another-model");
    }


    private static ModelOptions fullCatalog() {
        return new ModelOptions(
            "openai-codex",
            "example-fast",
            List.of(new HermesModelCatalog.Provider(
                "openai-codex",
                "OpenAI",
                List.of("example-fast", "example-balanced", "example-deep", "example-pro", "another-model"),
                Map.of())),
            List.of());
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


    /** 그룹 10 이 {@code hidden} 을 숨긴 상태의 서비스와 대역이다. 칸은 모두 비었다. */
    private static HiddenFixtures hiddenFixtures(HiddenModels hidden) {
        ModelOptionsService options = mock(ModelOptionsService.class);
        ModelVisibilityService visibility = mock(ModelVisibilityService.class);
        when(visibility.hiddenFor(10L)).thenReturn(hidden);
        return new HiddenFixtures(
            service(
                new FakeModelSettingStore(),
                mock(AppUserRepository.class),
                options,
                visibility,
                mock(GroupModelCatalogService.class)),
            options,
            MEMBER,
            mock(Agent.class));
    }


    private record HiddenFixtures(ModelTierService service, ModelOptionsService options, CurrentUser user, Agent agent) {
    }


    /** 다섯 칸이 모두 provider 를 비운 정의로 찬 그룹이다. 에이전트의 목록은 openai-codex 하나다. */
    private static TierFixtures tierFixtures() {
        FakeModelSettingStore store = new FakeModelSettingStore().tiers(storedStops());
        AppUserRepository users = mock(AppUserRepository.class);
        ModelOptionsService options = mock(ModelOptionsService.class);
        Agent agent = mock(Agent.class);
        when(options.optionsForAgent(10L, agent))
            .thenReturn(new ModelOptions(
                "openai-codex",
                "example-fast",
                List.of(new HermesModelCatalog.Provider(
                    "openai-codex",
                    "OpenAI",
                    List.of("example-fast", "example-balanced", "example-deep", "example-pro"),
                    Map.of())),
                List.of()));
        return new TierFixtures(
            service(store, users, options, nothingHidden(), mock(GroupModelCatalogService.class)),
            store,
            users,
            options,
            MEMBER,
            agent);
    }


    private record TierFixtures(
        ModelTierService service,
        FakeModelSettingStore store,
        AppUserRepository users,
        ModelOptionsService options,
        CurrentUser user,
        Agent agent) {
    }


    /** 옮겨 온 정의처럼 provider 를 비운 다섯 칸이다. */
    private static ModelSetting.Stop[] storedStops() {
        return new ModelSetting.Stop[] {
            new ModelSetting.Stop(ModelTier.INSTANT, null, "example-fast", "low"),
            new ModelSetting.Stop(ModelTier.MEDIUM, null, "example-balanced", "medium"),
            new ModelSetting.Stop(ModelTier.HIGH, null, "example-deep", "high"),
            new ModelSetting.Stop(ModelTier.EXTRA_HIGH, null, "example-deep", "xhigh"),
            new ModelSetting.Stop(ModelTier.PRO, null, "example-pro", "max")};
    }


    /** provider 를 밝힌 다섯 칸이다. */
    private static ModelSetting.Stop[] explicitStops() {
        return new ModelSetting.Stop[] {
            new ModelSetting.Stop(ModelTier.INSTANT, "openai-codex", "example-fast", "low"),
            new ModelSetting.Stop(ModelTier.MEDIUM, "openai-codex", "example-balanced", "medium"),
            new ModelSetting.Stop(ModelTier.HIGH, "openai-codex", "example-deep", "high"),
            new ModelSetting.Stop(ModelTier.EXTRA_HIGH, "openai-codex", "example-deep", "xhigh"),
            new ModelSetting.Stop(ModelTier.PRO, "openai-codex", "example-pro", "max")};
    }


    private static ModelSetting.Stop[] fallbackStops() {
        return ModelSetting.empty().tiers().toArray(ModelSetting.Stop[]::new);
    }


    private static ModelOptions catalog(String provider, String model) {
        return new ModelOptions(
            provider,
            model,
            List.of(new HermesModelCatalog.Provider(provider, provider, List.of(model), Map.of())),
            List.of());
    }


    private static GroupModelCatalog groupCatalog(String provider, String... models) {
        return new GroupModelCatalog(
            List.of(new HermesModelCatalog.Provider(provider, provider, List.of(models), Map.of())),
            ModelChoice.REASONING_EFFORTS);
    }


    /** 칸 하나를 담는다. 이름은 서비스가 쓰지 않지만 화면이 보내는 값과 같게 둔다. */
    private static ModelTierOptions.Tier tier(ModelTier tier, String provider, String model, String effort) {
        return new ModelTierOptions.Tier(tier, labelOf(tier), provider, model, effort);
    }


    /** 주어진 칸만 채우고 나머지는 빈 칸으로 둔 다섯 칸이다. */
    private static List<ModelTierOptions.Tier> fiveTiers(ModelTierOptions.Tier... filled) {
        List<ModelTierOptions.Tier> tiers = new ArrayList<>();
        for (ModelTier each : ModelTier.values()) {
            tiers.add(tier(each, null, null, null));
        }
        for (ModelTierOptions.Tier one : filled) {
            tiers.set(one.tier().ordinal(), one);
        }
        return tiers;
    }


    private static String labelOf(ModelTier tier) {
        return switch (tier) {
            case INSTANT -> "Instant";
            case MEDIUM -> "Medium";
            case HIGH -> "High";
            case EXTRA_HIGH -> "Extra High";
            case PRO -> "Pro";
        };
    }
}
