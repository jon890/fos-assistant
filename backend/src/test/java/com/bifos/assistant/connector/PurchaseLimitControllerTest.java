package com.bifos.assistant.connector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.financial.application.PurchaseLimitSettings;
import com.bifos.assistant.connector.financial.application.dto.PurchaseLimitsView;
import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;
import com.bifos.assistant.connector.financial.presentation.PurchaseLimitController;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PurchaseLimitControllerTest {
    private static final CurrentUser MEMBER = new CurrentUser(7L, "member@example.com", "사용자", 1L, UserRole.MEMBER);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneOffset.UTC);
    private static final PurchaseLimitsView VIEW = new PurchaseLimitsView(50_000L, 100_000L, 0L, 100_000L);
    private final PurchaseLimitSettings settings = mock(PurchaseLimitSettings.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PurchaseLimitController(settings, new CurrentUserProvider(), CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    @Test
    @DisplayName("로그인한 사용자는 자기 구매 상한을 읽고 이름과 원 단위 정수로 바꾼다")
    void readsAndUpdatesOwnLimits() throws Exception {
        signIn(MEMBER);
        when(settings.view(MEMBER)).thenReturn(VIEW);
        when(settings.set(eq(MEMBER), eq(PurchaseLimitKind.ORDER), eq(50_000L), any())).thenReturn(VIEW);
        mvc.perform(get("/api/v1/purchase-limits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderKrw").value(50_000))
            .andExpect(jsonPath("$.dailyKrw").value(100_000))
            .andExpect(jsonPath("$.remainingTodayKrw").value(100_000));
        mvc.perform(put("/api/v1/purchase-limits/order").contentType(MediaType.APPLICATION_JSON).content("{\"value\":50000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderKrw").value(50_000));
    }


    @ParameterizedTest
    @ValueSource(strings = {"{\"value\":\"50000\"}", "{\"value\":1.5}", "{\"value\":null}", "{}", "{\"value\":1e3}"})
    @DisplayName("JSON 정수가 아닌 값은 입력 오류이고 바꾸지 않는다")
    void rejectsNonInteger(String body) throws Exception {
        signIn(MEMBER);
        mvc.perform(put("/api/v1/purchase-limits/daily").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(settings, never()).set(any(), any(), anyLong(), any());
    }


    @Test
    @DisplayName("모르는 이름은 입력 오류이고 바꾸지 않는다")
    void rejectsUnknownName() throws Exception {
        signIn(MEMBER);
        mvc.perform(put("/api/v1/purchase-limits/weekly").contentType(MediaType.APPLICATION_JSON).content("{\"value\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(settings, never()).set(any(), any(), anyLong(), any());
    }


    @Test
    @DisplayName("에이전트의 MCP 토큰으로 인증한 요청은 사용자가 아니라 구매 상한을 읽거나 바꾸지 못한다")
    void mcpPrincipalCannotUseLimitApi() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new McpPrincipal(1L, "profile", "0".repeat(64)), null, List.of()));
        mvc.perform(put("/api/v1/purchase-limits/order").contentType(MediaType.APPLICATION_JSON).content("{\"value\":999}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/purchase-limits")).andExpect(status().isUnauthorized());
        verify(settings, never()).set(any(), any(), anyLong(), any());
    }


    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
