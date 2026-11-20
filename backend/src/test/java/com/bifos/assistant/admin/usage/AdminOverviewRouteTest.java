package com.bifos.assistant.admin.usage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.admin.usage.application.AdminOverviewService;
import com.bifos.assistant.admin.usage.application.dto.OverviewFailures;
import com.bifos.assistant.admin.usage.presentation.AdminOverviewController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 관리자 요약 경로의 권한과 응답 모양을 HTTP 경계에서 본다. */
class AdminOverviewRouteTest {

    private static final CurrentUser MEMBER = new CurrentUser(1L, "kid@example.com", "아이", 10L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(2L, "dad@example.com", "아빠", 10L, UserRole.ADMIN);

    private final AdminOverviewService overview = mock(AdminOverviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminOverviewController(new CurrentUserProvider(), overview))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();


    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }


    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }


    @Test
    @DisplayName("MEMBER 역할은 두 경로 모두 403 이고 그룹을 세지 않는다")
    void forbidsMember() throws Exception {
        signIn(MEMBER);

        mvc.perform(get("/api/v1/admin/overview/failures")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/overview/monthly")).andExpect(status().isForbidden());

        verifyNoInteractions(overview);
    }


    @Test
    @DisplayName("실패 줄에는 대화 번호 없이 이름과 오류 코드와 내 것인지만 있다")
    void failureLineCarriesOnlyNamesCodeAndOwnership() throws Exception {
        signIn(ADMIN);
        Instant at = Instant.parse("2026-11-20T00:00:00Z");
        when(overview.failures(ADMIN)).thenReturn(new OverviewFailures(
            at, 1, List.of(new OverviewFailures.Line(7L, "아이", "요약 비서", at, "HERMES_BUSY", false))));

        mvc.perform(get("/api/v1/admin/overview/failures"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.recent[0].userName").value("아이"))
            .andExpect(jsonPath("$.recent[0].errorCode").value("HERMES_BUSY"))
            .andExpect(jsonPath("$.recent[0].mine").value(false))
            .andExpect(jsonPath("$.recent[0].conversationId").doesNotExist());
    }
}
