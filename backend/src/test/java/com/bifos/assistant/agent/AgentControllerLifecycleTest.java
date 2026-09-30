package com.bifos.assistant.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 에이전트를 만들고, 공개 범위를 바꾸고, 지우는 경로가 HTTP 경계에서 돌려주는 상태 코드와 응답 모양을 본다.
 *
 * <p>순서와 권한 판정은 서비스 테스트가 본다. 여기서는 서비스를 대역으로 두고, 상태 코드와 요청자 기준의
 * {@code editable}, {@code ownedByMe} 가 맞게 채워지는지만 본다.
 */
class AgentControllerLifecycleTest {

    private static final CurrentUser KID =
            new CurrentUser(7L, "kid@example.com", "아이", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN =
            new CurrentUser(9L, "dad@example.com", "아빠", 1L, UserRole.ADMIN);

    private final AgentRepository agents = mock(AgentRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AgentController(new AgentService(agents), currentUser, lifecycle))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(KID);
    }

    @Test
    @DisplayName("만들면 201 과 요청자가 주인인 에이전트를 준다")
    void returns201WithRequesterAsOwnerOnCreate() throws Exception {
        when(lifecycle.create(KID, "숙제 도우미", null))
                .thenReturn(agent("a0123456789", AgentVisibility.PRIVATE, KID.id()));

        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"숙제 도우미\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("a0123456789"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.starterPrompts").doesNotExist())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.ownedByMe").value(true));
    }

    @Test
    @DisplayName("상한이면 409 AGENT LIMIT REACHED 다")
    void returns409AgentLimitReachedAtLimit() throws Exception {
        when(lifecycle.create(KID, "여섯째", AgentVisibility.GROUP))
                .thenThrow(new ApiException(ErrorCode.AGENT_LIMIT_REACHED, "an agent limit of 5 was reached"));

        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"여섯째\",\"visibility\":\"GROUP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_LIMIT_REACHED.name()));
    }

    @Test
    @DisplayName("공개 범위를 바꾸면 200 과 바뀐 에이전트를 준다")
    void returns200WithUpdatedAgentOnVisibilityChange() throws Exception {
        when(lifecycle.changeVisibility(KID, "a0123456789", AgentVisibility.GROUP))
                .thenReturn(agent("a0123456789", AgentVisibility.GROUP, KID.id()));

        mvc.perform(patch("/api/v1/agents/{code}/visibility", "a0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"GROUP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("GROUP"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.ownedByMe").value(true));
    }

    /** {@code ADMIN} 은 남의 에이전트를 관리하지만 주인은 아니다. 화면이 「다른 사람 것」 을 이 값으로 가른다. */
    @Test
    @DisplayName("ADMIN 이 남의 에이전트를 바꾸면 관리할 수 있지만 주인은 아니다")
    void adminEditingOthersAgentCanManageButIsNotOwner() throws Exception {
        when(currentUser.require()).thenReturn(ADMIN);
        when(lifecycle.changeVisibility(ADMIN, "a0123456789", AgentVisibility.PRIVATE))
                .thenReturn(agent("a0123456789", AgentVisibility.PRIVATE, KID.id()));

        mvc.perform(patch("/api/v1/agents/{code}/visibility", "a0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.ownedByMe").value(false));
    }

    @Test
    @DisplayName("공개 범위가 비면 VALIDATION FAILED 이고 서비스를 부르지 않는다")
    void returnsValidationFailedWithoutCallingServiceOnBlankVisibility() throws Exception {
        mvc.perform(patch("/api/v1/agents/{code}/visibility", "a0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verify(lifecycle, never()).changeVisibility(any(), anyString(), any());
    }

    @Test
    @DisplayName("지우면 204 이고 본문이 없다")
    void returns204WithoutBodyOnDelete() throws Exception {
        mvc.perform(delete("/api/v1/agents/{code}", "a0123456789"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(lifecycle).delete(KID, "a0123456789");
    }

    @Test
    @DisplayName("목록은 요청자 기준으로 관리 가능과 주인 여부를 채운다")
    void fillsManageableAndOwnerFlagsPerRequester() throws Exception {
        Agent mine = agent("a-mine", AgentVisibility.PRIVATE, KID.id());
        Agent shared = agent("a-shared", AgentVisibility.GROUP, ADMIN.id());
        when(agents.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(mine, shared));

        mvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("a-mine"))
                .andExpect(jsonPath("$[0].editable").value(true))
                .andExpect(jsonPath("$[0].ownedByMe").value(true))
                .andExpect(jsonPath("$[1].code").value("a-shared"))
                .andExpect(jsonPath("$[1].editable").value(false))
                .andExpect(jsonPath("$[1].ownedByMe").value(false));
    }

    private static Agent agent(String code, AgentVisibility visibility, Long ownerUserId) {
        return Agent.of(code, "숙제 도우미", "ua-" + code, "http://agent-runtime.test/p/ua-" + code,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, visibility, ownerUserId);
    }
}
