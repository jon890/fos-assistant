package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.HiddenModels;
import com.bifos.assistant.chat.application.ModelOptions;
import com.bifos.assistant.chat.application.ModelOptionsService;
import com.bifos.assistant.chat.application.ModelVisibilityService;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.HermesModelCatalog.Provider;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모델 목록을 profile 마다 들고 있는 것과, 들고 있던 목록으로 Hermes 실패를 넘기는 것을 본다.
 *
 * <p>에이전트 확인은 실제 {@link AgentService} 가 판정하게 하고 저장소만 대역으로 둔다. 대역으로 바꾸면
 * 대화를 보낼 때와 같은 판정을 쓰는지 볼 수 없다.
 */
class ModelOptionsServiceTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final CurrentUser dad = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private final HermesModelClient hermes = mock(HermesModelClient.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final ModelVisibilityService visibility = mock(ModelVisibilityService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private ModelOptionsService service;

    @BeforeEach
    void setUp() {
        when(visibility.hiddenFor(1L)).thenReturn(HiddenModels.none());
        service = new ModelOptionsService(new AgentService(agentRepository), hermes, visibility, TTL, clock);
        agentOf("dad", "dad-profile", true);
        agentOf("kid", "kid-profile", true);
    }

    private static HermesModelCatalog catalog(String defaultProvider, Provider... providers) {
        return new HermesModelCatalog(defaultProvider, "example-model", List.of(providers));
    }

    private static Provider provider(String slug, String... models) {
        return new Provider(slug, slug, List.of(models), Map.of());
    }

    private void agentOf(String code, String profile, boolean enabled) {
        Agent agent = Agent.of(
                code,
                code,
                profile,
                "http://agent-runtime.test/p/" + profile,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                dad.id());
        agent.changeAccess(enabled, AgentVisibility.PRIVATE, dad.id());
        when(agentRepository.findByCode(code)).thenReturn(Optional.of(agent));
    }

    @Test
    @DisplayName("같은 profile 을 두 번 물으면 Hermes 를 한 번만 부른다")
    void callsHermesOnlyOnceForSameProfileAskedTwice() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")));

        ModelOptions first = service.optionsFor(dad, "dad");
        ModelOptions second = service.optionsFor(dad, "dad");

        assertThat(second).isEqualTo(first);
        assertThat(first.defaultProvider()).isEqualTo("openai-codex");
        assertThat(first.defaultModel()).isEqualTo("example-model");
        assertThat(first.providers()).extracting(Provider::models).containsExactly(List.of("a"));
        verify(hermes, times(1)).readCatalog("http://agent-runtime.test/p/dad-profile", "dad-profile");
    }

    @Test
    @DisplayName("들고 있는 시간이 지나기 직전까지는 들고 있고 지나면 다시 부른다")
    void holdsUntilJustBeforeExpiryAndCallsAgainAfter() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "old")))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "new")));

        service.optionsFor(dad, "dad");
        clock.advance(TTL.minusSeconds(1));
        assertThat(service.optionsFor(dad, "dad").providers().get(0).models()).containsExactly("old");
        verify(hermes, times(1)).readCatalog(anyString(), anyString());

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.optionsFor(dad, "dad").providers().get(0).models()).containsExactly("new");
        verify(hermes, times(2)).readCatalog(anyString(), anyString());
    }

    @Test
    @DisplayName("다시 읽다 Hermes 가 실패하면 들고 있던 목록을 돌려주고 다음에 다시 읽는다")
    void returnsHeldListAndRereadsNextTimeWhenRereadFailsWithHermes() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "old")))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "new")));
        service.optionsFor(dad, "dad");

        clock.advance(TTL);
        ModelOptions stale = service.optionsFor(dad, "dad");
        ModelOptions recovered = service.optionsFor(dad, "dad");

        assertThat(stale.providers().get(0).models()).containsExactly("old");
        assertThat(recovered.providers().get(0).models()).containsExactly("new");
        verify(hermes, times(3)).readCatalog(anyString(), anyString());
    }

    @Test
    @DisplayName("profile 기본값은 들고 있는 목록에서 읽어 Hermes 를 다시 부르지 않는다")
    void readsProfileDefaultFromHeldCatalog() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")));
        Agent agent = agentRepository.findByCode("dad").orElseThrow();
        service.optionsFor(dad, "dad");

        ModelChoice profileDefault = service.profileDefaultOf(agent);

        assertThat(profileDefault).isEqualTo(ModelChoice.stored("openai-codex", "example-model", null));
        verify(hermes, times(1)).readCatalog(anyString(), anyString());
    }

    @Test
    @DisplayName("profile 기본값을 다시 읽다 Hermes 가 실패하면 들고 있던 값을 돌려준다")
    void returnsHeldProfileDefaultWhenRereadFails() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        Agent agent = agentRepository.findByCode("dad").orElseThrow();
        service.profileDefaultOf(agent);

        clock.advance(TTL);

        assertThat(service.profileDefaultOf(agent))
                .isEqualTo(ModelChoice.stored("openai-codex", "example-model", null));
        verify(hermes, times(2)).readCatalog(anyString(), anyString());
    }

    @Test
    @DisplayName("profile 기본값을 한 번도 읽지 못했으면 HERMES UNAVAILABLE 을 삼키지 않는다")
    void profileDefaultThrowsHermesUnavailableWhenNeverRead() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        Agent agent = agentRepository.findByCode("dad").orElseThrow();

        assertThatThrownBy(() -> service.profileDefaultOf(agent))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
    }

    @Test
    @DisplayName("처음 읽다 실패하면 HERMES UNAVAILABLE 이고 실패를 들고 있지 않는다")
    void firstReadFailureIsHermesUnavailableAndFailureIsNotHeld() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")));

        assertThatThrownBy(() -> service.optionsFor(dad, "dad"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
        assertThat(service.optionsFor(dad, "dad").providers()).hasSize(1);
    }

    @Test
    @DisplayName("다시 읽다 key 가 없어져도 그 예외를 올린다")
    void rethrowsWhenKeyDisappearsDuringReread() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")))
                .thenThrow(new ApiException(ErrorCode.HERMES_PROFILE_KEY_MISSING, "no key"));
        service.optionsFor(dad, "dad");

        clock.advance(TTL);

        assertThatThrownBy(() -> service.optionsFor(dad, "dad"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_PROFILE_KEY_MISSING));
    }

    @Test
    @DisplayName("profile 이 다르면 따로 부른다")
    void callsSeparatelyForDifferentProfile() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")));

        service.optionsFor(dad, "dad");
        service.optionsFor(dad, "kid");

        verify(hermes).readCatalog("http://agent-runtime.test/p/dad-profile", "dad-profile");
        verify(hermes).readCatalog("http://agent-runtime.test/p/kid-profile", "kid-profile");
    }

    @Test
    @DisplayName("agentCode 가 비었거나 없는 에이전트는 AGENT NOT FOUND 이고 Hermes 를 부르지 않는다")
    void blankAgentCodeOrMissingAgentIsAgentNotFoundWithoutCallingHermes() {
        when(agentRepository.findByCode("ghost")).thenReturn(Optional.empty());

        for (String code : new String[] {null, "", "  ", "ghost"}) {
            assertThatThrownBy(() -> service.optionsFor(dad, code))
                    .as("agentCode=%s", code)
                    .isInstanceOfSatisfying(
                            ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        }
        verify(hermes, never()).readCatalog(anyString(), anyString());
    }

    @Test
    @DisplayName("꺼진 에이전트는 AGENT DISABLED 이고 Hermes 를 부르지 않는다")
    void disabledAgentIsAgentDisabledWithoutCallingHermes() {
        agentOf("off", "off-profile", false);

        assertThatThrownBy(() -> service.optionsFor(dad, "off"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_DISABLED));
        verify(hermes, never()).readCatalog(anyString(), anyString());
    }

    @Test
    @DisplayName("기본 provider 를 맨 앞에 두고 나머지는 Hermes 가 준 차례를 지킨다")
    void putsDefaultProviderFirstAndKeepsHermesOrderForRest() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog(
                        "openai-codex", provider("alpha", "a"), provider("openai-codex", "b"), provider("beta", "c")));

        ModelOptions options = service.optionsFor(dad, "dad");

        assertThat(options.providers()).extracting(Provider::slug).containsExactly("openai-codex", "alpha", "beta");
    }

    @Test
    @DisplayName("기본 provider 가 목록에 없으면 Hermes 가 준 차례 그대로다")
    void keepsHermesOrderWhenDefaultProviderNotInList() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog(null, provider("alpha", "a"), provider("beta", "b")));

        assertThat(service.optionsFor(dad, "dad").providers())
                .extracting(Provider::slug)
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("reasoning 표에 모든 모델이 있고 Hermes 가 밝히지 않은 모델은 참이다")
    void reasoningTableHasAllModelsAndUnstatedModelsAreTrue() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog(
                        "openai-codex",
                        new Provider(
                                "openai-codex",
                                "OpenAI Codex",
                                List.of("example-model", "example-model-mini", "example-model-new"),
                                Map.of("example-model", true, "example-model-mini", false))));

        Map<String, Boolean> reasoning =
                service.optionsFor(dad, "dad").providers().get(0).reasoning();

        assertThat(reasoning)
                .isEqualTo(Map.of("example-model", true, "example-model-mini", false, "example-model-new", true));
    }

    @Test
    @DisplayName("reasoningEfforts 는 대화가 고를 수 있는 effort 와 같다")
    void reasoningEffortsEqualEffortsConversationCanChoose() {
        when(hermes.readCatalog(anyString(), anyString())).thenReturn(catalog("openai-codex"));

        assertThat(service.optionsFor(dad, "dad").reasoningEfforts()).isEqualTo(ModelChoice.REASONING_EFFORTS);
    }

    /** 시각을 손으로 옮기는 시계다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
