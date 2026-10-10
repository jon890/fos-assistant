package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.admin.application.AgentAdminService;
import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.KnownFlows;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentAdminController;
import com.bifos.assistant.agent.presentation.AgentDtos.AgentView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 관리자가 「먼저 살펴보기에 쓰기 도구 허용」 을 켜고 끄는 길을 실제 역할 판정으로 본다(ADR-082).
 *
 * <p>요청자는 보안 문맥에 넣고 {@link CurrentUserProvider} 는 실제 것을 쓴다. 저장소는 대역이고 모든 값은 합성이다.
 */
class AgentProactiveCheckWritesAdminTest {

    private static final String PATH = "/api/v1/admin/agents/career";
    private static final CurrentUser ADMIN = new CurrentUser(1L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);
    private static final CurrentUser MEMBER = new CurrentUser(2L, "member@example.com", "사용자", 1L, UserRole.MEMBER);

    private final AgentRepository agents = mock(AgentRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentAdminController(
                    new AgentAdminService(
                            agents,
                            mock(AppUserRepository.class),
                            mock(AgentLifecycleService.class),
                            mock(AgentEndpointProbe.class),
                            mock(KnownFlows.class),
                            mock(AgentConnectorBindings.class),
                            Clock.systemUTC()),
                    new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = Agent.of(
                "career",
                "커리어",
                "career",
                "http://agent-runtime.test/p/career",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                ADMIN.id(),
                Instant.parse("2026-10-01T00:00:00Z"));
        when(agents.findByCodeForUpdate("career")).thenAnswer(call -> Optional.of(agent));
        when(agents.save(any(Agent.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("관리자가 켜면 저장되고 칸을 비운 다른 수정은 값을 두며 끄면 다시 거짓이다")
    void adminTurnsWritesOnKeepsItWhenOmittedAndTurnsItOff() throws Exception {
        signIn(ADMIN);

        send("{\"enabled\":true,\"visibility\":\"PRIVATE\",\"proactiveCheckWritesAllowed\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proactiveCheckWritesAllowed").value(true));
        assertThat(agent.proactiveCheckWritesAllowed()).as("켠 뒤의 값").isTrue();

        send("{\"enabled\":true,\"visibility\":\"PRIVATE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proactiveCheckWritesAllowed").value(true));
        assertThat(agent.proactiveCheckWritesAllowed()).as("칸을 비운 수정 뒤의 값").isTrue();

        send("{\"enabled\":true,\"visibility\":\"PRIVATE\",\"proactiveCheckWritesAllowed\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proactiveCheckWritesAllowed").value(false));
        assertThat(agent.proactiveCheckWritesAllowed()).as("끈 뒤의 값").isFalse();
    }

    @Test
    @DisplayName("MEMBER 가 켜려 하면 403 FORBIDDEN 이고 값이 그대로다")
    void memberCannotTurnWritesOn() throws Exception {
        signIn(MEMBER);

        send("{\"enabled\":true,\"visibility\":\"PRIVATE\",\"proactiveCheckWritesAllowed\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(agent.proactiveCheckWritesAllowed()).isFalse();
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("커넥터 에이전트에는 쓰기 허용 값을 저장하지 않는다")
    void connectorAgentNeverStoresWrites() throws Exception {
        signIn(ADMIN);
        agent.markConnectorManaged();

        send("{\"enabled\":true,\"visibility\":\"PRIVATE\",\"proactiveCheckWritesAllowed\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(agent.proactiveCheckWritesAllowed()).isFalse();
        verify(agents, never()).save(any(Agent.class));
    }

    @Test
    @DisplayName("사용자에게 가는 에이전트 응답에는 쓰기 허용 칸이 없다")
    void userAgentViewHasNoWritesField() {
        List<String> names = Arrays.stream(AgentView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(names).doesNotContain("proactiveCheckWritesAllowed");
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
