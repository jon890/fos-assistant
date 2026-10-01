package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
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
    void setUp() {
        when(currentUser.require()).thenReturn(MEMBER);
        when(currentUser.requireAdmin()).thenReturn(ADMIN);
    }

    @Test
    @DisplayName("등록 본문의 profile은 무시하고 로그인 사용자로 등록한다")
    void registersForLoggedInUserIgnoringProfileInBody() throws Exception {
        when(service.register(eq(MEMBER), any(), any())).thenReturn(snapshot(ConnectionStatus.PENDING, "fab_abcd"));

        mvc.perform(post("/api/v1/connections/accountbook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fab_" + "a".repeat(43) + "\",\"profile\":\"other-profile\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenPrefix").value("fab_abcd"));

        verify(service).register(MEMBER, "fab_" + "a".repeat(43), null);
    }

    @Test
    @DisplayName("토큰 요청의 문자열 표현과 잘못된 JSON은 원문을 노출하지 않는다")
    void hidesTokenInToStringAndMalformedJson() throws Exception {
        String token = "fab_" + "a".repeat(43);
        assertThat(new ConnectionDtos.RegisterRequest(token, null).toString()).doesNotContain(token);
        var result = mvc.perform(post("/api/v1/connections/accountbook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":[\"" + token + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("인증되지 않은 연결 조회는 401을 돌려준다")
    void returns401ForUnauthenticatedConnectionLookup() throws Exception {
        when(currentUser.require()).thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));

        mvc.perform(get("/api/v1/connections/accountbook"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("가족 목록은 로그인 사용자의 토큰으로 읽고 토큰은 응답하지 않는다")
    void readsFamiliesWithUserTokenWithoutReturningToken() throws Exception {
        String token = "fab_" + "a".repeat(43);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        when(service.availableFamilies(MEMBER, token))
                .thenReturn(List.of(new com.bifos.assistant.connector.application.AccountbookTokenVerifier.FamilyOption(
                        uuid, "공유 가족")));
        var result = mvc.perform(post("/api/v1/connections/accountbook/families")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uuid").value(uuid.toString()))
                .andExpect(jsonPath("$[0].name").value("공유 가족"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        verify(service).availableFamilies(MEMBER, token);
    }

    @Test
    @DisplayName("확인은 요청 본문 없이 로그인 사용자로 수행한다")
    void verifiesForLoggedInUserWithoutRequestBody() throws Exception {
        when(service.check(MEMBER)).thenReturn(snapshot(ConnectionStatus.PENDING, "fab_abcd"));

        mvc.perform(post("/api/v1/connections/accountbook/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(service).check(MEMBER);
    }

    @Test
    @DisplayName("MEMBER는 관리자 목록을 읽지 못한다")
    void forbidsMemberFromReadingAdminList() throws Exception {
        when(currentUser.requireAdmin())
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "administrator access is required"));

        mvc.perform(get("/api/v1/admin/connections/accountbook"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));

        verify(service, never()).listForAdmin(any());
    }

    @Test
    @DisplayName("관리자 목록은 다른 사용자의 prefix와 family를 내보내지 않는다")
    void adminListHidesOtherUsersPrefixAndFamily() throws Exception {
        when(service.listForAdmin(ADMIN))
                .thenReturn(
                        List.of(new AdminConnectionSnapshot(7L, "사용자", ConnectionStatus.PENDING, "agent-code", true)));

        mvc.perform(get("/api/v1/admin/connections/accountbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[0].tokenPrefix").doesNotExist())
                .andExpect(jsonPath("$[0].familyUuid").doesNotExist());
    }

    @Test
    @DisplayName("관리자 반영 확인 응답은 prefix와 family를 내보내지 않는다")
    void adminConfirmResponseHidesPrefixAndFamily() throws Exception {
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
