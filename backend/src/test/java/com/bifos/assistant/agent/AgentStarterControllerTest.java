package com.bifos.assistant.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.StarterService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentStarterPrompt;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentStarterPromptRepository;
import com.bifos.assistant.agent.presentation.AgentStarterController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.domain.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 소개와 추천 질문 경로가 HTTP 경계에서 돌려주는 상태 코드와 응답 모양을 본다.
 *
 * <p>줄마다의 길이 상한은 요청 본문 검증이 걸고, 수의 상한은 빈 줄을 버린 뒤 서비스가 건다. 두 자리가
 * 모두 컨트롤러 밖이라 컨트롤러를 직접 불러서는 드러나지 않는다.
 */
class AgentStarterControllerTest {

    private static final CurrentUser OWNER =
            new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentStarterPromptRepository prompts = mock(AgentStarterPromptRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);

    /** 가짜 저장소가 받은 줄. 다시 읽으면 이것을 돌려준다. */
    private final List<AgentStarterPrompt> saved = new ArrayList<>();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AgentStarterController(
                    new StarterService(new AgentService(agents), agents, prompts), currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void 준비한다() {
        Agent dad = Agent.of("dad", "dad", "dad-profile",
                "http://127.0.0.1:1/p/dad-profile", "openai-codex", "example-model",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE,
                OWNER.id());
        when(agents.findByCode("dad")).thenReturn(Optional.of(dad));
        when(agents.findByIdForUpdate(any())).thenReturn(Optional.of(dad));
        when(prompts.save(any())).thenAnswer(invocation -> {
            AgentStarterPrompt prompt = invocation.getArgument(0);
            saved.add(prompt);
            return prompt;
        });
        when(prompts.findByAgentIdOrderByPositionAsc(any())).thenAnswer(invocation -> List.copyOf(saved));
        when(currentUser.require()).thenReturn(OWNER);
    }

    @Test
    void 읽으면_최대_수를_함께_준다() throws Exception {
        mvc.perform(get("/api/v1/agents/dad/starters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagline").doesNotExist())
                .andExpect(jsonPath("$.starterPrompts").isEmpty())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.maxPrompts").value(4));
    }

    @Test
    void 상한을_넘는_줄은_거절하고_쓰지_않는다() throws Exception {
        mvc.perform(write("{\"tagline\":\"소개\",\"starterPrompts\":[\"" + "가".repeat(301) + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        Assertions.assertThat(saved).isEmpty();
    }

    /** 길이의 상한은 앞뒤 공백을 뗀 뒤에 센다. 요청 본문 검증이 공백까지 세어 거절하면 안 된다. */
    @Test
    void 앞뒤_공백이_붙은_상한_길이의_소개와_줄은_쓴다() throws Exception {
        String tagline = "가".repeat(200);
        String prompt = "나".repeat(300);
        mvc.perform(write("{\"tagline\":\"  " + tagline + "  \",\"starterPrompts\":[\"  " + prompt + "  \"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagline").value(tagline))
                .andExpect(jsonPath("$.starterPrompts[0]").value(prompt));
    }

    /** 수의 상한은 빈 줄을 버린 뒤에 센다. 요청 본문 검증이 다섯 줄이라는 것만으로 거절하면 안 된다. */
    @Test
    void 빈_줄을_포함한_다섯_줄은_넷으로_쓴다() throws Exception {
        mvc.perform(write("{\"tagline\":\"소개\",\"starterPrompts\":[\"a\",\" \",\"b\",\"c\",\"d\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagline").value("소개"))
                .andExpect(jsonPath("$.starterPrompts.length()").value(4))
                .andExpect(jsonPath("$.starterPrompts[3]").value("d"));
    }

    @Test
    void 빈_줄을_버려도_넷을_넘으면_거절한다() throws Exception {
        mvc.perform(write("{\"starterPrompts\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        Assertions.assertThat(saved).isEmpty();
    }

    private static org.springframework.test.web.servlet.RequestBuilder write(String json) {
        return put("/api/v1/agents/{code}/starters", "dad")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
