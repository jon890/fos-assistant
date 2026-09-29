package com.bifos.assistant.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.bifos.assistant.agent.application.StarterService;
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
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
    private final StarterService starters = mock(StarterService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AgentController(new AgentService(agents), starters, currentUser, lifecycle))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void 준비한다() {
        when(currentUser.require()).thenReturn(KID);
        // 대역의 에이전트는 저장되지 않아 번호가 비어 있다. 빈 번호로 찾아도 되는 표를 돌려준다.
        when(starters.promptsOf(anyList())).thenAnswer(call -> new HashMap<Long, List<String>>());
    }

    @Test
    void 만들면_201_과_요청자가_주인인_에이전트를_준다() throws Exception {
        when(lifecycle.create(KID, "숙제 도우미", null))
                .thenReturn(agent("a0123456789", AgentVisibility.PRIVATE, KID.id()));

        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"숙제 도우미\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("a0123456789"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.starterPrompts").isEmpty())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.ownedByMe").value(true));
    }

    @Test
    void 상한이면_409_AGENT_LIMIT_REACHED_다() throws Exception {
        when(lifecycle.create(KID, "여섯째", AgentVisibility.GROUP))
                .thenThrow(new ApiException(ErrorCode.AGENT_LIMIT_REACHED, "an agent limit of 5 was reached"));

        mvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"여섯째\",\"visibility\":\"GROUP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_LIMIT_REACHED.name()));
    }

    @Test
    void 공개_범위를_바꾸면_200_과_바뀐_에이전트를_준다() throws Exception {
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
    void ADMIN_이_남의_에이전트를_바꾸면_관리할_수_있지만_주인은_아니다() throws Exception {
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
    void 공개_범위가_비면_VALIDATION_FAILED_이고_서비스를_부르지_않는다() throws Exception {
        mvc.perform(patch("/api/v1/agents/{code}/visibility", "a0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verify(lifecycle, never()).changeVisibility(any(), anyString(), any());
    }

    @Test
    void 지우면_204_이고_본문이_없다() throws Exception {
        mvc.perform(delete("/api/v1/agents/{code}", "a0123456789"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(lifecycle).delete(KID, "a0123456789");
    }

    @Test
    void 목록은_요청자_기준으로_관리_가능과_주인_여부를_채운다() throws Exception {
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
