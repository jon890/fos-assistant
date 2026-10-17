package com.bifos.assistant.connector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.application.FinancialLimitSettings;
import com.bifos.assistant.connector.application.model.FinancialLimitsView;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.presentation.financial.FinancialLimitController;
import com.bifos.assistant.mcp.application.dto.McpPrincipal;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinancialLimitControllerTest {
    private static final CurrentUser MEMBER = new CurrentUser(7L, "member@example.com", "사용자", 1L, UserRole.MEMBER);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC);
    private static final FinancialLimitsView VIEW = new FinancialLimitsView(true,
        List.of(new FinancialLimitsView.Item("order_amount_usd", "USD", "한 건 달러 금액", "500", "1000", true)),
        Map.of("USD", "0"), Map.of("USD", "1000"));
    private final FinancialLimitSettings settings = mock(FinancialLimitSettings.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FinancialLimitController(settings, new CurrentUserProvider(), CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    @Test
    @DisplayName("로그인한 사용자는 자기 상한을 읽고 이름과 십진 글로 바꾼다")
    void readsAndUpdatesOwnLimits() throws Exception {
        signIn(MEMBER);
        when(settings.view(eq(MEMBER), any())).thenReturn(VIEW);
        when(settings.set(eq(MEMBER), eq(FinancialLimitKind.ORDER_AMOUNT_USD), eq(new BigDecimal("500")), any())).thenReturn(VIEW);
        mvc.perform(get("/api/v1/financial-limits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.limits[0].name").value("order_amount_usd"))
            .andExpect(jsonPath("$.remainingToday.USD").value("1000"));
        mvc.perform(put("/api/v1/financial-limits/order_amount_usd").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"500\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limits[0].value").value("500"));
        mvc.perform(put("/api/v1/financial-limits/order_amount_usd").contentType(MediaType.APPLICATION_JSON).content("{\"value\":null}"))
            .andExpect(status().isOk());
        verify(settings).set(eq(MEMBER), eq(FinancialLimitKind.ORDER_AMOUNT_USD), isNull(), any());
    }


    @Test
    @DisplayName("모르는 이름과 십진 글이 아닌 값은 입력 오류이고 바꾸지 않는다")
    void rejectsUnknownNameAndMalformedValue() throws Exception {
        signIn(MEMBER);
        mvc.perform(put("/api/v1/financial-limits/weekly_amount").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/v1/financial-limits/order_amount_usd").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"1e3\"}"))
            .andExpect(status().isBadRequest());
        verify(settings, never()).set(any(), any(), any(), any());
    }


    @Test
    @DisplayName("에이전트의 MCP 토큰으로 인증한 요청은 사용자가 아니라 상한을 읽거나 바꾸지 못한다")
    void mcpPrincipalCannotUseLimitApi() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new McpPrincipal(1L, "profile", "0".repeat(64)), null, List.of()));
        mvc.perform(put("/api/v1/financial-limits/order_amount_usd").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"999\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/financial-limits")).andExpect(status().isUnauthorized());
        verify(settings, never()).set(any(), any(), any(), any());
    }


    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
