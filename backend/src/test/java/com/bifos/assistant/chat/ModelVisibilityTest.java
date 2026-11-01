package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelOptions;
import com.bifos.assistant.chat.modelchoice.application.ModelOptionsService;
import com.bifos.assistant.chat.modelchoice.application.ModelVisibilityService;
import com.bifos.assistant.chat.modelchoice.domain.GroupSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSettingId;
import com.bifos.assistant.chat.modelchoice.infra.GroupSettingRepository;
import com.bifos.assistant.hermes.infra.HermesModelClient;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog.Provider;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 그룹의 모델 숨김이 목록, 저장 검증, 실행 검증에 같은 규칙으로 걸리는지 본다. */
@BackendIntegrationTest
class ModelVisibilityTest {

    private static final Long GROUP_ID = 9_000_000_003L;
    private static final Long OTHER_GROUP_ID = 9_000_000_004L;

    private final CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", GROUP_ID, UserRole.ADMIN);
    private final CurrentUser member = new CurrentUser(2L, "member@example.com", "사용자", GROUP_ID, UserRole.MEMBER);

    @Autowired
    ModelVisibilityService visibility;

    @Autowired
    GroupSettingRepository groupSettings;

    @Autowired
    TransactionTemplate transactions;


    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> {
            groupSettings.deleteById(new GroupSettingId(GROUP_ID, GroupSetting.MODEL));
            groupSettings.deleteById(new GroupSettingId(OTHER_GROUP_ID, GroupSetting.MODEL));
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
            .extracting(error -> ((ApiException)error).code())
            .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> visibility.save(admin, List.of(new HiddenModels.Entry(" ", "model"))))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException)error).code())
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
                .extracting(error -> ((ApiException)error).code())
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
        assertThat(filtered.defaultAvailable()).isFalse();
        assertThat(unfiltered.providers()).extracting(Provider::slug).containsExactly("provider-b", "provider-a");
        assertThat(unfiltered.defaultAvailable()).isTrue();
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
            hermes, visibility, Duration.ofMinutes(10), Clock.systemUTC());

        assertThat(options.optionsForAgent(GROUP_ID, agent).defaultAvailable()).isTrue();
    }


    private ModelOptionsService optionsService(Agent agent) {
        HermesModelClient hermes = mock(HermesModelClient.class);
        HermesModelCatalog catalog = new HermesModelCatalog(
            "provider-b",
            "model-old",
            List.of(
                new Provider("provider-a", "A", List.of("model-a"), Map.of()),
                new Provider("provider-b", "B", List.of("model-old", "model-new"), Map.of())));
        when(hermes.readCatalog(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(catalog);
        when(hermes.readCatalog(agent.apiBaseUrl(), agent.hermesProfile(), true)).thenReturn(catalog);
        return new ModelOptionsService(
            hermes, visibility, Duration.ofMinutes(10), Clock.systemUTC());
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
            null);
    }
}
