package com.bifos.assistant.chat.modelchoice;

import static com.bifos.assistant.hermes.application.dto.ReasoningCapability.Support.SUPPORTED;
import static com.bifos.assistant.hermes.application.dto.ReasoningCapability.Support.UNKNOWN;
import static com.bifos.assistant.hermes.application.dto.ReasoningCapability.Support.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.modelchoice.application.GroupModelCatalogService;
import com.bifos.assistant.chat.modelchoice.application.ModelOptionsService;
import com.bifos.assistant.chat.modelchoice.application.ModelVisibilityService;
import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelCatalog;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog.Provider;
import com.bifos.assistant.hermes.application.dto.ReasoningCapability;
import com.bifos.assistant.hermes.infra.HermesModelClient;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 그룹 목록이 켜진 에이전트 profile 목록의 교집합이고 숨길 모델의 후보가 합집합인지 본다.
 *
 * <p>에이전트 판정과 profile 목록 캐시는 실제 서비스가 하게 하고 저장소와 Hermes 만 대역으로 둔다.
 */
class GroupModelCatalogServiceTest {

    private static final Long GROUP_ID = 1L;

    private final HermesModelClient hermes = mock(HermesModelClient.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final ModelVisibilityService visibility = mock(ModelVisibilityService.class);
    private final List<Agent> agents = new ArrayList<>();

    private GroupModelCatalogService service;


    @BeforeEach
    void setUp() {
        when(visibility.hiddenFor(GROUP_ID)).thenReturn(HiddenModels.none());
        when(agentRepository.findByEnabledTrueOrderByCodeAsc()).thenReturn(agents);
        AgentService agentService = new AgentService(agentRepository);
        ModelOptionsService options = new ModelOptionsService(
            hermes, visibility, Duration.ofMinutes(10));
        service = new GroupModelCatalogService(agentService, options);
    }


    private void agentOf(String code, String profile) {
        agents.add(Agent.of(
            code,
            code,
            profile,
            "http://agent-runtime.test/p/" + profile,
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.GROUP,
            1L));
    }


    private void catalogOf(String profile, Provider... providers) {
        when(hermes.readCatalog("http://agent-runtime.test/p/" + profile, profile))
            .thenReturn(new HermesModelCatalog(null, null, List.of(providers)));
    }


    private static Provider provider(String slug, String... models) {
        return new Provider(slug, slug + " 이름", List.of(models), Map.of());
    }


    @Test
    @DisplayName("모든 profile 에 있는 provider 와 모델만 첫 profile 의 차례대로 남긴다")
    void keepsOnlyProvidersAndModelsEveryProfileOffersInFirstProfileOrder() {
        agentOf("dad", "dad-profile");
        agentOf("kid", "kid-profile");
        catalogOf("dad-profile", provider("openai-codex", "b", "a", "c"), provider("anthropic", "x"));
        catalogOf("kid-profile", provider("openai-codex", "a", "b"), provider("openrouter", "x"));

        GroupModelCatalog catalog = service.catalogFor(GROUP_ID);

        assertThat(catalog.providers()).extracting(Provider::slug).containsExactly("openai-codex");
        assertThat(catalog.providers().getFirst().name()).isEqualTo("openai-codex 이름");
        assertThat(catalog.providers().getFirst().models()).containsExactly("b", "a");
        assertThat(catalog.reasoningEfforts()).isEqualTo(ModelChoice.REASONING_EFFORTS);
    }


    @Test
    @DisplayName("같은 profile 을 가리키는 에이전트는 한 번만 읽는다")
    void readsSharedProfileOnlyOnce() {
        agentOf("dad", "shared-profile");
        agentOf("mom", "shared-profile");
        catalogOf("shared-profile", provider("openai-codex", "a"));

        assertThat(service.catalogFor(GROUP_ID).providers()).hasSize(1);
        verify(hermes, times(1)).readCatalog(eq("http://agent-runtime.test/p/shared-profile"), eq("shared-profile"));
    }


    @Test
    @DisplayName("profile 마다 밝힌 reasoning 지원이 다르면 그 칸은 모름으로 둔다")
    void marksReasoningUnknownWhenProfilesDisagree() {
        agentOf("dad", "dad-profile");
        agentOf("kid", "kid-profile");
        catalogOf("dad-profile", new Provider("openai-codex", "ChatGPT", List.of("a"), Map.of(
            "a", new ReasoningCapability(SUPPORTED, SUPPORTED))));
        catalogOf("kid-profile", new Provider("openai-codex", "ChatGPT", List.of("a"), Map.of(
            "a", new ReasoningCapability(SUPPORTED, UNSUPPORTED))));

        ReasoningCapability capability = service.catalogFor(GROUP_ID).providers().getFirst().reasoning().get("a");

        assertThat(capability).isEqualTo(new ReasoningCapability(SUPPORTED, UNKNOWN));
    }


    @Test
    @DisplayName("그룹이 숨긴 모델은 그룹 목록에도 없다")
    void leavesOutHiddenModels() {
        when(visibility.hiddenFor(GROUP_ID)).thenReturn(new HiddenModels(List.of(new HiddenModels.Entry("openai-codex", "a"))));
        agentOf("dad", "dad-profile");
        catalogOf("dad-profile", provider("openai-codex", "a", "b"));

        assertThat(service.catalogFor(GROUP_ID).providers().getFirst().models()).containsExactly("b");
    }


    @Test
    @DisplayName("켜진 에이전트가 없으면 빈 목록이다")
    void returnsEmptyListWithoutEnabledAgents() {
        assertThat(service.catalogFor(GROUP_ID).providers()).isEmpty();
    }


    @Test
    @DisplayName("한 profile 이라도 목록을 한 번도 읽지 못했으면 읽기 실패를 그대로 올린다")
    void propagatesFailureWhenAnyProfileWasNeverRead() {
        agentOf("dad", "dad-profile");
        agentOf("kid", "kid-profile");
        catalogOf("dad-profile", provider("openai-codex", "a"));
        when(hermes.readCatalog("http://agent-runtime.test/p/kid-profile", "kid-profile"))
            .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.catalogFor(GROUP_ID))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }


    @Test
    @DisplayName("숨길 모델의 후보는 어느 profile 에든 있는 provider 와 모델을 첫 profile 의 차례 뒤에 붙여 모두 담는다")
    void hideCandidatesAreTheUnionOfEveryProfile() {
        agentOf("dad", "dad-profile");
        agentOf("kid", "kid-profile");
        catalogOf("dad-profile", provider("openai-codex", "b", "a"), provider("anthropic", "x"));
        catalogOf("kid-profile", provider("openai-codex", "a", "c"), provider("openrouter", "y"));

        GroupModelCatalog candidates = service.hideCandidates();

        assertThat(candidates.providers()).extracting(Provider::slug).containsExactly("openai-codex", "anthropic", "openrouter");
        assertThat(candidates.providers().getFirst().models()).containsExactly("b", "a", "c");
        assertThat(candidates.reasoningEfforts()).isEqualTo(ModelChoice.REASONING_EFFORTS);
        // 같은 profile 들의 교집합이면 한 profile 에만 있는 모델은 숨길 방법이 없다
        assertThat(service.catalogFor(GROUP_ID).providers().getFirst().models()).containsExactly("a");
    }


    @Test
    @DisplayName("숨길 모델의 후보에는 이미 숨긴 모델도 남아 풀 수 있다")
    void hideCandidatesKeepAlreadyHiddenModels() {
        when(visibility.hiddenFor(GROUP_ID)).thenReturn(new HiddenModels(List.of(new HiddenModels.Entry("openai-codex", "a"))));
        agentOf("dad", "dad-profile");
        catalogOf("dad-profile", provider("openai-codex", "a", "b"), provider("anthropic", "x"));

        GroupModelCatalog candidates = service.hideCandidates();

        assertThat(candidates.providers().getFirst().models()).containsExactly("a", "b");
        assertThat(service.catalogFor(GROUP_ID).providers().getFirst().models()).containsExactly("b");
    }


    @Test
    @DisplayName("profile 마다 밝힌 reasoning 지원이 다르면 후보의 그 칸은 모름이고 한 profile 에만 있는 모델은 그 profile 의 값이다")
    void hideCandidatesMergeReasoningSupport() {
        agentOf("dad", "dad-profile");
        agentOf("kid", "kid-profile");
        catalogOf("dad-profile", new Provider("openai-codex", "ChatGPT", List.of("a", "only-dad"), Map.of(
            "a", new ReasoningCapability(SUPPORTED, SUPPORTED),
            "only-dad", new ReasoningCapability(SUPPORTED, SUPPORTED))));
        catalogOf("kid-profile", new Provider("openai-codex", "ChatGPT", List.of("a"), Map.of(
            "a", new ReasoningCapability(SUPPORTED, UNSUPPORTED))));

        Provider merged = service.hideCandidates().providers().getFirst();

        assertThat(merged.reasoning().get("a")).isEqualTo(new ReasoningCapability(SUPPORTED, UNKNOWN));
        assertThat(merged.reasoning().get("only-dad")).isEqualTo(new ReasoningCapability(SUPPORTED, SUPPORTED));
    }


    @Test
    @DisplayName("숨길 모델의 후보도 한 profile 이라도 목록을 한 번도 읽지 못했으면 읽기 실패를 그대로 올린다")
    void hideCandidatesPropagateFailureWhenAnyProfileWasNeverRead() {
        agentOf("dad", "dad-profile");
        agentOf("kid", "kid-profile");
        catalogOf("dad-profile", provider("openai-codex", "a"));
        when(hermes.readCatalog("http://agent-runtime.test/p/kid-profile", "kid-profile"))
            .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.hideCandidates())
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }
}
