package com.bifos.assistant.connector;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.application.AccountbookConnectionService;
import com.bifos.assistant.connector.application.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.ConnectionSnapshot;
import com.bifos.assistant.connector.domain.ConnectionStatus;
import com.bifos.assistant.connector.presentation.AccountbookConnectionAdminController;
import com.bifos.assistant.connector.presentation.AccountbookConnectionController;
import com.bifos.assistant.connector.presentation.ConnectionDtos;
import static org.assertj.core.api.Assertions.assertThat;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.user.domain.UserRole;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountbookConnectionControllerTest {
    private static final CurrentUser MEMBER = new CurrentUser(7L, "member@example.com", "사용자", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(8L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    private final AccountbookConnectionService service = mock(AccountbookConnectionService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AccountbookConnectionController(service, currentUser),
                    new AccountbookConnectionAdminController(service, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void 준비한다() {
        when(currentUser.require()).thenReturn(MEMBER);
        when(currentUser.requireAdmin()).thenReturn(ADMIN);
    }

    @Test
    void 등록_본문의_profile은_무시하고_로그인_사용자로_등록한다() throws Exception {
        when(service.register(eq(MEMBER), any(), any())).thenReturn(snapshot(ConnectionStatus.PENDING, "fab_abcd"));

        mvc.perform(post("/api/v1/connections/accountbook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fab_" + "a".repeat(43) + "\",\"profile\":\"other-profile\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenPrefix").value("fab_abcd"));

        verify(service).register(MEMBER, "fab_" + "a".repeat(43), null);
    }

    @Test
    void 토큰_요청의_문자열_표현과_잘못된_JSON은_원문을_노출하지_않는다() throws Exception {
        String token = "fab_" + "a".repeat(43);
        assertThat(new ConnectionDtos.RegisterRequest(token, null).toString()).doesNotContain(token);
        var result = mvc.perform(post("/api/v1/connections/accountbook")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":[\"" + token + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        verifyNoInteractions(service);
    }

    @Test
    void 인증되지_않은_연결_조회는_401을_돌려준다() throws Exception {
        when(currentUser.require()).thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));

        mvc.perform(get("/api/v1/connections/accountbook"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(service);
    }

    @Test
    void 가족_목록은_로그인_사용자의_토큰으로_읽고_토큰은_응답하지_않는다() throws Exception {
        String token = "fab_" + "a".repeat(43);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        when(service.availableFamilies(MEMBER, token)).thenReturn(List.of(
                new com.bifos.assistant.connector.application.AccountbookTokenVerifier.FamilyOption(uuid, "공유 가족")));
        var result = mvc.perform(post("/api/v1/connections/accountbook/families")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].uuid").value(uuid.toString()))
                .andExpect(jsonPath("$[0].name").value("공유 가족")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        verify(service).availableFamilies(MEMBER, token);
    }

    @Test
    void 확인은_요청_본문_없이_로그인_사용자로_수행한다() throws Exception {
        when(service.check(MEMBER)).thenReturn(snapshot(ConnectionStatus.PENDING, "fab_abcd"));

        mvc.perform(post("/api/v1/connections/accountbook/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(service).check(MEMBER);
    }

    @Test
    void MEMBER는_관리자_목록을_읽지_못한다() throws Exception {
        when(currentUser.requireAdmin()).thenThrow(new ApiException(ErrorCode.FORBIDDEN, "administrator access is required"));

        mvc.perform(get("/api/v1/admin/connections/accountbook"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));

        verify(service, never()).listForAdmin(any());
    }

    @Test
    void 관리자_목록은_다른_사용자의_prefix와_family를_내보내지_않는다() throws Exception {
        when(service.listForAdmin(ADMIN)).thenReturn(List.of(
                new AdminConnectionSnapshot(7L, "사용자", ConnectionStatus.PENDING, "agent-code", true)));

        mvc.perform(get("/api/v1/admin/connections/accountbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[0].tokenPrefix").doesNotExist())
                .andExpect(jsonPath("$[0].familyUuid").doesNotExist());
    }

    @Test
    void 관리자_반영_확인_응답은_prefix와_family를_내보내지_않는다() throws Exception {
        when(service.confirmApplied(ADMIN, 7L)).thenReturn(snapshot(ConnectionStatus.READY, "fab_abcd"));

        mvc.perform(post("/api/v1/admin/connections/accountbook/7/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.tokenPrefix").doesNotExist())
                .andExpect(jsonPath("$.familyUuid").doesNotExist());
    }

    private static ConnectionSnapshot snapshot(ConnectionStatus status, String prefix) {
        return new ConnectionSnapshot(status, prefix, null, false, null, "agent-code");
    }
}
