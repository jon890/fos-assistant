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
import com.bifos.assistant.chat.application.ModelOptions;
import com.bifos.assistant.chat.application.ModelOptionsService;
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
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private ModelOptionsService service;

    @BeforeEach
    void 준비한다() {
        service = new ModelOptionsService(new AgentService(agentRepository), hermes, TTL, clock);
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
        Agent agent = Agent.of(code, code, profile, "http://agent-runtime.test/p/" + profile,
                "openai-codex", "example-model", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, dad.id());
        agent.changeAccess(enabled, AgentVisibility.PRIVATE, dad.id());
        when(agentRepository.findByCode(code)).thenReturn(Optional.of(agent));
    }

    @Test
    void 같은_profile_을_두_번_물으면_Hermes_를_한_번만_부른다() {
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
    void 들고_있는_시간이_지나기_직전까지는_들고_있고_지나면_다시_부른다() {
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
    void 다시_읽다_Hermes_가_실패하면_들고_있던_목록을_돌려주고_다음에_다시_읽는다() {
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
    void 처음_읽다_실패하면_HERMES_UNAVAILABLE_이고_실패를_들고_있지_않는다() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")));

        assertThatThrownBy(() -> service.optionsFor(dad, "dad"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));
        assertThat(service.optionsFor(dad, "dad").providers()).hasSize(1);
    }

    @Test
    void 다시_읽다_key_가_없어져도_그_예외를_올린다() {
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
    void profile_이_다르면_따로_부른다() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", provider("openai-codex", "a")));

        service.optionsFor(dad, "dad");
        service.optionsFor(dad, "kid");

        verify(hermes).readCatalog("http://agent-runtime.test/p/dad-profile", "dad-profile");
        verify(hermes).readCatalog("http://agent-runtime.test/p/kid-profile", "kid-profile");
    }

    @Test
    void agentCode_가_비었거나_없는_에이전트는_AGENT_NOT_FOUND_이고_Hermes_를_부르지_않는다() {
        when(agentRepository.findByCode("ghost")).thenReturn(Optional.empty());

        for (String code : new String[] {null, "", "  ", "ghost"}) {
            assertThatThrownBy(() -> service.optionsFor(dad, code))
                    .as("agentCode=%s", code)
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
        }
        verify(hermes, never()).readCatalog(anyString(), anyString());
    }

    @Test
    void 꺼진_에이전트는_AGENT_DISABLED_이고_Hermes_를_부르지_않는다() {
        agentOf("off", "off-profile", false);

        assertThatThrownBy(() -> service.optionsFor(dad, "off"))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_DISABLED));
        verify(hermes, never()).readCatalog(anyString(), anyString());
    }

    @Test
    void 기본_provider_를_맨_앞에_두고_나머지는_Hermes_가_준_차례를_지킨다() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog(
                        "openai-codex",
                        provider("alpha", "a"),
                        provider("openai-codex", "b"),
                        provider("beta", "c")));

        ModelOptions options = service.optionsFor(dad, "dad");

        assertThat(options.providers()).extracting(Provider::slug).containsExactly("openai-codex", "alpha", "beta");
    }

    @Test
    void 기본_provider_가_목록에_없으면_Hermes_가_준_차례_그대로다() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog(null, provider("alpha", "a"), provider("beta", "b")));

        assertThat(service.optionsFor(dad, "dad").providers())
                .extracting(Provider::slug)
                .containsExactly("alpha", "beta");
    }

    @Test
    void reasoning_표에_모든_모델이_있고_Hermes_가_밝히지_않은_모델은_참이다() {
        when(hermes.readCatalog(anyString(), anyString()))
                .thenReturn(catalog("openai-codex", new Provider("openai-codex", "OpenAI Codex",
                        List.of("example-model", "example-model-mini", "example-model-new"),
                        Map.of("example-model", true, "example-model-mini", false))));

        Map<String, Boolean> reasoning = service.optionsFor(dad, "dad").providers().get(0).reasoning();

        assertThat(reasoning).isEqualTo(Map.of(
                "example-model", true, "example-model-mini", false, "example-model-new", true));
    }

    @Test
    void reasoningEfforts_는_대화가_고를_수_있는_effort_와_같다() {
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
