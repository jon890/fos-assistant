package com.bifos.assistant.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PersonaService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentPersonaController;
import com.bifos.assistant.hermes.StubHermesDashboardClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.user.domain.type.UserRole;
import java.time.Instant;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 성격 경로가 HTTP 경계에서 돌려주는 상태 코드와 응답 모양을 본다.
 *
 * <p>본문 상한과 볼 수 없는 에이전트를 숨기는 것은 컨트롤러를 직접 불러서는 드러나지 않는다. 본문
 * 검증이 걸리는 자리와 오류를 상태 코드로 옮기는 자리가 컨트롤러 밖이기 때문이다.
 */
class AgentPersonaControllerTest {

    private static final String SOUL = "너는 아빠의 비서다.\n";

    private static final CurrentUser OWNER = new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final CurrentUser OTHER = new CurrentUser(8L, "mom@example.com", "엄마", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(9L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    private final AgentRepository agents = mock(AgentRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final StubHermesDashboardClient dashboard = new StubHermesDashboardClient();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AgentPersonaController(new PersonaService(new AgentService(agents), dashboard), currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        when(agents.findByCode("dad"))
                .thenReturn(Optional.of(agent("dad", "dad-profile", AgentVisibility.PRIVATE, OWNER.id())));
        when(agents.findByCode("home"))
                .thenReturn(Optional.of(agent("home", "home-profile", AgentVisibility.GROUP, null)));
        dashboard.seedSoul("dad-profile", SOUL);
        dashboard.seedSoul("home-profile", SOUL);
        when(currentUser.require()).thenReturn(OWNER);
    }

    @Test
    @DisplayName("자기만 보는 자기 에이전트를 주인이 읽으면 고칠 수 있다")
    void ownerCanEditOwnPrivateAgent() throws Exception {
        mvc.perform(get("/api/v1/agents/dad/persona"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(SOUL))
                .andExpect(jsonPath("$.bodyHash").value(Sha256.hex16(SOUL)))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.maxChars").value(8000));
    }

    @Test
    @DisplayName("남의 자기만 보는 에이전트는 없는 것과 같은 응답을 준다")
    void othersPrivateAgentRespondsAsMissing() throws Exception {
        when(currentUser.require()).thenReturn(OTHER);

        mvc.perform(get("/api/v1/agents/dad/persona"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("그룹 공용 에이전트를 MEMBER 가 읽으면 고칠 수 없다")
    void memberCannotEditGroupSharedAgent() throws Exception {
        when(currentUser.require()).thenReturn(OTHER);

        mvc.perform(get("/api/v1/agents/home/persona"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    @DisplayName("그룹 공용 에이전트를 MEMBER 가 쓰면 거절하고 쓰지 않는다")
    void memberWritingGroupSharedAgentIsRejectedWithoutWrite() throws Exception {
        when(currentUser.require()).thenReturn(OTHER);

        mvc.perform(write("home", "새 성격", Sha256.hex16(SOUL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));

        Assertions.assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    @DisplayName("그룹 공용 에이전트를 ADMIN 은 쓸 수 있다")
    void adminCanWriteGroupSharedAgent() throws Exception {
        when(currentUser.require()).thenReturn(ADMIN);

        mvc.perform(write("home", "새 성격", Sha256.hex16(SOUL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("새 성격"));
    }

    @Test
    @DisplayName("상한을 넘는 본문은 거절한다")
    void rejectsBodyOverLimit() throws Exception {
        mvc.perform(write("dad", "가".repeat(8001), Sha256.hex16(SOUL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        Assertions.assertThat(dashboard.soulWrites()).isEmpty();
    }

    @Test
    @DisplayName("대시보드에 닿지 못하면 그것으로 알린다")
    void reportsDashboardUnreachable() throws Exception {
        dashboard.failOnReadSoul(() -> new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not reach Hermes"));

        mvc.perform(get("/api/v1/agents/dad/persona"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(ErrorCode.HERMES_UNAVAILABLE.name()));
    }

    private static RequestBuilder write(String code, String body, String baseHash) {
        return put("/api/v1/agents/{code}/persona", code)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":%s,\"baseHash\":\"%s\"}".formatted(quote(body), baseHash));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static Agent agent(String code, String profile, AgentVisibility visibility, Long ownerUserId) {
        return Agent.of(
                code,
                code,
                profile,
                "http://127.0.0.1:1/p/" + profile,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                visibility,
                ownerUserId,
                Instant.now());
    }
}
