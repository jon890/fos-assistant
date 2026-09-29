package com.bifos.assistant.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.StarterStatus;
import com.bifos.assistant.agent.application.StarterSuggestionService;
import com.bifos.assistant.agent.application.StarterSuggestions;
import com.bifos.assistant.agent.presentation.AgentStarterController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 추천 질문 경로가 HTTP 경계에서 돌려주는 응답 모양을 본다.
 *
 * <p>추천을 언제 만들고 무엇을 돌려줄지는 {@code StarterSuggestionServiceTest} 가 본다. 볼 수 없는 에이전트의
 * {@code AGENT_NOT_FOUND} 도 거기서 확인한다.
 */
class AgentStarterControllerTest {

    private static final CurrentUser DAD =
            new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final StarterSuggestionService starters = mock(StarterSuggestionService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AgentStarterController(starters, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void 준비한다() {
        when(currentUser.require()).thenReturn(DAD);
    }

    @Test
    void 만들어_둔_추천은_prompts_와_READY_로_준다() throws Exception {
        when(starters.read(DAD, "dad")).thenReturn(
                new StarterSuggestions(List.of("일정 정리해 줘", "장보기 목록 만들어 줘"), StarterStatus.READY));

        mvc.perform(get("/api/v1/agents/dad/starters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.prompts.length()").value(2))
                .andExpect(jsonPath("$.prompts[0]").value("일정 정리해 줘"))
                .andExpect(jsonPath("$.prompts[1]").value("장보기 목록 만들어 줘"))
                .andExpect(jsonPath("$.tagline").doesNotExist())
                .andExpect(jsonPath("$.starterPrompts").doesNotExist());
    }

    @Test
    void 만드는_중이면_빈_prompts_와_GENERATING_을_준다() throws Exception {
        when(starters.read(DAD, "dad")).thenReturn(new StarterSuggestions(List.of(), StarterStatus.GENERATING));

        mvc.perform(get("/api/v1/agents/dad/starters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.prompts").isArray())
                .andExpect(jsonPath("$.prompts").isEmpty());
    }

    /** 사람이 적던 추천을 쓰는 경로는 없앴다. */
    @Test
    void 추천을_쓰는_PUT_은_받지_않는다() throws Exception {
        mvc.perform(put("/api/v1/agents/{code}/starters", "dad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starterPrompts\":[\"a\"]}"))
                .andExpect(status().isMethodNotAllowed());
    }
}
