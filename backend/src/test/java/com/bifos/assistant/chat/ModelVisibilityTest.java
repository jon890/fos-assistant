package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.AgentModelDefaultService;
import com.bifos.assistant.chat.application.AgentModelSettings;
import com.bifos.assistant.chat.application.HiddenModels;
import com.bifos.assistant.chat.application.ModelOptions;
import com.bifos.assistant.chat.application.ModelOptionsService;
import com.bifos.assistant.chat.application.ModelVisibilityService;
import com.bifos.assistant.chat.infra.ModelHiddenRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.HermesModelCatalog.Provider;
import com.bifos.assistant.hermes.dto.ReasoningCapability;
import com.bifos.assistant.hermes.dto.ReasoningCapability.Support;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 그룹의 모델 숨김과 에이전트 기본 모델이 목록, 저장 검증, 실행 검증에 같은 규칙으로 걸리는지 본다. */
@BackendIntegrationTest
class ModelVisibilityTest {

    private static final Long GROUP_ID = 9_000_000_003L;
    private static final Long OTHER_GROUP_ID = 9_000_000_004L;

    private final CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", GROUP_ID, UserRole.ADMIN);
    private final CurrentUser member = new CurrentUser(2L, "member@example.com", "사용자", GROUP_ID, UserRole.MEMBER);

    @Autowired
    ModelVisibilityService visibility;

    @Autowired
    ModelHiddenRepository hidden;

    @Autowired
    TransactionTemplate transactions;

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> {
            hidden.deleteByGroupId(GROUP_ID);
            hidden.deleteByGroupId(OTHER_GROUP_ID);
        });
    }

    @Test
    @DisplayName("숨김 목록을 저장하면 provider 전체와 모델 하나를 따로 숨기고 다시 저장하면 통째로 바뀐다")
    void savingReplacesTheWholeHiddenList() {
        visibility.save(
                admin,
                List.of(
                        new HiddenModels.Entry(" provider-a ", null),
                        new HiddenModels.Entry("provider-b", " model-old "),
                        new HiddenModels.Entry("provider-b", "model-old")));

        HiddenModels saved = visibility.hiddenFor(GROUP_ID);
        assertThat(saved.entries())
                .containsExactly(
                        new HiddenModels.Entry("provider-a", null), new HiddenModels.Entry("provider-b", "model-old"));
        assertThat(saved.hides("provider-a", "anything")).isTrue();
        assertThat(saved.hides("provider-b", "model-old")).isTrue();
        assertThat(saved.hides("provider-b", "model-new")).isFalse();

        visibility.save(admin, List.of(new HiddenModels.Entry("provider-c", null)));

        assertThat(visibility.hiddenFor(GROUP_ID).entries())
                .containsExactly(new HiddenModels.Entry("provider-c", null));
    }

    @Test
    @DisplayName("다른 그룹의 숨김은 적용하지 않는다")
    void hiddenListIsScopedToTheGroup() {
        visibility.save(admin, List.of(new HiddenModels.Entry("provider-a", null)));

        assertThat(visibility.hiddenFor(OTHER_GROUP_ID).entries()).isEmpty();
        visibility.requireVisible(OTHER_GROUP_ID, ModelChoice.stored("provider-a", "model", null));
    }

    @Test
    @DisplayName("관리자가 아니면 숨김 목록을 바꾸지 못하고 provider 가 빈 항목은 저장하지 못한다")
    void rejectsMemberAndBlankProvider() {
        assertThatThrownBy(() -> visibility.save(member, List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> visibility.save(admin, List.of(new HiddenModels.Entry(" ", "model"))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("숨긴 모델을 명시한 선택은 MODEL_HIDDEN 으로 거절하고 모델을 고르지 않은 선택은 통과한다")
    void requireVisibleRejectsHiddenChoice() {
        visibility.save(admin, List.of(new HiddenModels.Entry("provider-b", "model-old")));

        visibility.requireVisible(GROUP_ID, ModelChoice.defaults());
        visibility.requireVisible(GROUP_ID, ModelChoice.stored("provider-b", "model-new", null));
        assertThatThrownBy(
                        () -> visibility.requireVisible(GROUP_ID, ModelChoice.stored("provider-b", "model-old", null)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
    }

    @Test
    @DisplayName("목록은 숨긴 provider 와 모델을 빼고, 기본 모델이 숨겨졌으면 쓸 수 없다고 알린다")
    void optionsLeaveOutHiddenProvidersAndModels() {
        visibility.save(
                admin,
                List.of(new HiddenModels.Entry("provider-a", null), new HiddenModels.Entry("provider-b", "model-old")));
        Agent agent = agent();
        ModelOptionsService options = optionsService(agent);

        ModelOptions filtered = options.optionsForAgent(GROUP_ID, agent);
        ModelOptions unfiltered = options.unfilteredForAgent(agent);

        assertThat(filtered.providers()).extracting(Provider::slug).containsExactly("provider-b");
        assertThat(filtered.providers().get(0).models()).containsExactly("model-new");
        assertThat(filtered.defaultModel()).isEqualTo("model-old");
        assertThat(filtered.defaultFromAgent()).isFalse();
        assertThat(filtered.defaultAvailable()).isFalse();
        assertThat(unfiltered.providers()).extracting(Provider::slug).containsExactly("provider-b", "provider-a");
        assertThat(unfiltered.defaultAvailable()).isTrue();
    }

    @Test
    @DisplayName("에이전트 기본 모델이 있으면 목록의 기본 모델이 그 값이고 effort 도 함께 준다")
    void agentDefaultBecomesTheListedDefault() {
        Agent agent = agent();
        agent.changeDefaultModel("provider-b", "model-new", "high");
        ModelOptions options = optionsService(agent).optionsForAgent(GROUP_ID, agent);

        assertThat(options.defaultProvider()).isEqualTo("provider-b");
        assertThat(options.defaultModel()).isEqualTo("model-new");
        assertThat(options.defaultReasoningEffort()).isEqualTo("high");
        assertThat(options.defaultFromAgent()).isTrue();
        assertThat(options.defaultAvailable()).isTrue();
    }

    @Test
    @DisplayName("에이전트 기본 모델 저장은 목록에 있고 숨기지 않은 모델만 받고, 비우면 profile 값으로 돌아간다")
    void agentDefaultIsValidatedOnSave() {
        visibility.save(admin, List.of(new HiddenModels.Entry("provider-a", null)));
        Agent agent = agent();
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.findByCode("helper")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("helper")).thenReturn(Optional.of(agent));
        when(agents.save(agent)).thenReturn(agent);
        AgentModelDefaultService defaults =
                new AgentModelDefaultService(new AgentService(agents), optionsService(agent), visibility);

        assertThatThrownBy(() -> defaults.save(member, "helper", ModelChoice.of("provider-b", "model-new", null)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> defaults.save(admin, "helper", ModelChoice.of("provider-a", "model-a", null)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.MODEL_HIDDEN);
        assertThatThrownBy(() -> defaults.save(admin, "helper", ModelChoice.of("provider-b", "missing", null)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        ModelChoice saved = defaults.save(admin, "helper", ModelChoice.of("provider-b", "model-new", "low"));
        AgentModelSettings settings = defaults.settingsFor(admin, "helper");

        assertThat(saved).isEqualTo(ModelChoice.stored("provider-b", "model-new", "low"));
        assertThat(settings.agentDefault()).isEqualTo(saved);
        assertThat(settings.catalog().defaultModel()).isEqualTo("model-old");
        assertThat(settings.hidden().entries()).containsExactly(new HiddenModels.Entry("provider-a", null));

        assertThat(defaults.save(admin, "helper", ModelChoice.defaults())).isEqualTo(ModelChoice.defaults());
        assertThat(agent.defaultModel()).isNull();
    }

    @Test
    @DisplayName("목록에서 빠진 모델을 쓰던 에이전트는 모델을 그대로 두고 effort 만 바꿔 저장할 수 있다")
    void unchangedModelSkipsCatalogValidation() {
        Agent agent = agent();
        agent.changeDefaultModel("provider-b", "model-gone", "low");
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.findByCode("helper")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("helper")).thenReturn(Optional.of(agent));
        when(agents.save(agent)).thenReturn(agent);
        AgentModelDefaultService defaults =
                new AgentModelDefaultService(new AgentService(agents), optionsService(agent), visibility);

        ModelChoice saved = defaults.save(admin, "helper", ModelChoice.of("provider-b", "model-gone", "high"));

        assertThat(saved).isEqualTo(ModelChoice.stored("provider-b", "model-gone", "high"));
    }

    @Test
    @DisplayName("에이전트 기본 effort none 은 끄기 지원이 SUPPORTED 인 모델이나 profile 기본 모델에서만 저장한다")
    void agentDefaultNoneIsSavedOnlyWhereDisablingIsSupported() {
        Agent agent = agent();
        AgentModelDefaultService defaults = defaultsWith(agent, disableCatalog("can-off"));

        ModelChoice profileDefault = defaults.save(admin, "helper", ModelChoice.of(null, null, "none"));
        assertThat(profileDefault).as("모델을 비우면 profile 기본 모델로 판정한다").isEqualTo(ModelChoice.stored(null, null, "none"));

        ModelChoice explicit = defaults.save(admin, "helper", ModelChoice.of("provider-c", "can-off", "none"));
        assertThat(explicit).isEqualTo(ModelChoice.stored("provider-c", "can-off", "none"));

        for (String model : List.of("cannot-off", "unsaid")) {
            assertThatThrownBy(() -> defaults.save(admin, "helper", ModelChoice.of("provider-c", model, "none")))
                    .as("모델 %s", model)
                    .isInstanceOf(ApiException.class)
                    .extracting(error -> ((ApiException) error).code())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThat(agent.defaultModel()).as("거절한 저장은 기본값을 바꾸지 않는다").isEqualTo("can-off");
        assertThat(agent.defaultReasoningEffort()).isEqualTo("none");
    }

    @Test
    @DisplayName("profile 기본 모델의 끄기 지원을 모르면 모델을 비운 none 을 저장하지 않는다")
    void agentDefaultNoneWithoutModelIsRejectedWhenProfileDefaultCannotBeDisabled() {
        Agent agent = agent();
        AgentModelDefaultService defaults = defaultsWith(agent, disableCatalog("unsaid"));

        assertThatThrownBy(() -> defaults.save(admin, "helper", ModelChoice.of(null, null, "none")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(agent.defaultReasoningEffort()).isNull();
    }

    @Test
    @DisplayName("Hermes 가 목록을 답하지 못해도 저장된 기본값과 숨김 목록은 돌려준다")
    void settingsSurviveCatalogFailure() {
        visibility.save(admin, List.of(new HiddenModels.Entry("provider-a", null)));
        Agent agent = agent();
        agent.changeDefaultModel("provider-b", "model-new", null);
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.findByCode("helper")).thenReturn(Optional.of(agent));
        HermesModelClient hermes = mock(HermesModelClient.class);
        when(hermes.readCatalog(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        ModelOptionsService options = new ModelOptionsService(
                mock(AgentService.class), hermes, visibility, Duration.ofMinutes(10), Clock.systemUTC());
        AgentModelDefaultService defaults = new AgentModelDefaultService(new AgentService(agents), options, visibility);

        AgentModelSettings settings = defaults.settingsFor(admin, "helper");

        assertThat(settings.catalog()).isNull();
        assertThat(settings.agentDefault()).isEqualTo(ModelChoice.stored("provider-b", "model-new", null));
        assertThat(settings.hidden().entries()).containsExactly(new HiddenModels.Entry("provider-a", null));
    }

    @Test
    @DisplayName("대소문자만 다른 숨김 항목은 먼저 온 것 하나만 저장한다")
    void caseOnlyDuplicatesAreSavedOnce() {
        visibility.save(
                admin,
                List.of(new HiddenModels.Entry("Provider-A", "Model"), new HiddenModels.Entry("provider-a", "model")));

        assertThat(visibility.hiddenFor(GROUP_ID).entries())
                .containsExactly(new HiddenModels.Entry("Provider-A", "Model"));
    }

    @Test
    @DisplayName("Hermes 가 기본 provider 를 주지 않아도 목록에 있는 기본 모델은 쓸 수 있다고 답한다")
    void defaultIsAvailableWithoutDefaultProvider() {
        Agent agent = agent();
        HermesModelClient hermes = mock(HermesModelClient.class);
        when(hermes.readCatalog(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(new HermesModelCatalog(
                        null, "model-old", List.of(new Provider("provider-b", "B", List.of("model-old"), Map.of()))));
        ModelOptionsService options = new ModelOptionsService(
                mock(AgentService.class), hermes, visibility, Duration.ofMinutes(10), Clock.systemUTC());

        assertThat(options.optionsForAgent(GROUP_ID, agent).defaultAvailable()).isTrue();
    }

    /** 끄기 지원이 SUPPORTED, UNSUPPORTED, UNKNOWN 인 모델을 한 provider 에 둔 목록이다. */
    private static HermesModelCatalog disableCatalog(String profileDefaultModel) {
        return new HermesModelCatalog(
                "provider-c",
                profileDefaultModel,
                List.of(new Provider(
                        "provider-c",
                        "C",
                        List.of("can-off", "cannot-off", "unsaid"),
                        Map.of(
                                "can-off", new ReasoningCapability(Support.SUPPORTED, Support.SUPPORTED),
                                "cannot-off", new ReasoningCapability(Support.SUPPORTED, Support.UNSUPPORTED)))));
    }

    private AgentModelDefaultService defaultsWith(Agent agent, HermesModelCatalog catalog) {
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.findByCode("helper")).thenReturn(Optional.of(agent));
        when(agents.findByCodeForUpdate("helper")).thenReturn(Optional.of(agent));
        when(agents.save(agent)).thenReturn(agent);
        HermesModelClient hermes = mock(HermesModelClient.class);
        when(hermes.readCatalog(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(catalog);
        ModelOptionsService options = new ModelOptionsService(
                mock(AgentService.class), hermes, visibility, Duration.ofMinutes(10), Clock.systemUTC());
        return new AgentModelDefaultService(new AgentService(agents), options, visibility);
    }

    private ModelOptionsService optionsService(Agent agent) {
        HermesModelClient hermes = mock(HermesModelClient.class);
        when(hermes.readCatalog(agent.apiBaseUrl(), agent.hermesProfile()))
                .thenReturn(new HermesModelCatalog(
                        "provider-b",
                        "model-old",
                        List.of(
                                new Provider("provider-a", "A", List.of("model-a"), Map.of()),
                                new Provider("provider-b", "B", List.of("model-old", "model-new"), Map.of()))));
        return new ModelOptionsService(
                mock(AgentService.class), hermes, visibility, Duration.ofMinutes(10), Clock.systemUTC());
    }

    private static Agent agent() {
        return Agent.of(
                "helper",
                "helper",
                "helper-profile",
                "http://hermes.invalid",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                null,
                Instant.now());
    }
}
