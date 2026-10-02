package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectorFieldSummary;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ConnectorOption;
import com.bifos.assistant.connector.application.model.ConnectorSummary;
import com.bifos.assistant.connector.application.model.ConnectorToolSummary;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.presentation.ConnectionDtos;
import com.bifos.assistant.connector.presentation.ConnectorConnectionAdminController;
import com.bifos.assistant.connector.presentation.ConnectorConnectionController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConnectorConnectionControllerTest {
    private static final String DEMO = "demo-notes";
    private static final String TOKEN = "demo_ok_0123456789";
    private static final String BODY = "{\"values\":{\"token\":\"" + TOKEN + "\"}}";
    private static final CurrentUser MEMBER = new CurrentUser(7L, "member@example.com", "사용자", 1L, UserRole.MEMBER);
    private static final CurrentUser ADMIN = new CurrentUser(8L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);

    private final ConnectorConnectionService service = mock(ConnectorConnectionService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ConnectorConnectionController(service, currentUser),
                    new ConnectorConnectionAdminController(service, currentUser))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        when(currentUser.require()).thenReturn(MEMBER);
        when(currentUser.requireAdmin()).thenReturn(ADMIN);
    }

    @Test
    @DisplayName("카탈로그는 칸 선언과 내 상태를 주고 env 이름을 내보내지 않는다")
    void catalogReturnsFieldsAndMyStatusWithoutEnvNames() throws Exception {
        when(service.catalog(MEMBER))
                .thenReturn(List.of(new ConnectorSummary(
                        DEMO,
                        "검사용 메모",
                        "검사에서만 쓰는 커넥터입니다.",
                        List.of(
                                new ConnectorFieldSummary("token", "토큰", "", true, true, "^demo_.+$", false, false),
                                new ConnectorFieldSummary("scope", "범위", "", false, false, null, true, true)),
                        List.of(new ConnectorToolSummary(
                                "write_note", "메모 쓰기", ToolRisk.WRITE, ToolApproval.REQUIRED, true)),
                        ConnectionStatus.PENDING,
                        true)));

        MvcResult result = mvc.perform(get("/api/v1/connectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DEMO))
                .andExpect(jsonPath("$[0].title").value("검사용 메모"))
                .andExpect(jsonPath("$[0].myStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].fields[0].key").value("token"))
                .andExpect(jsonPath("$[0].fields[0].secret").value(true))
                .andExpect(jsonPath("$[0].fields[0].pattern").value("^demo_.+$"))
                .andExpect(jsonPath("$[0].fields[1].hasOptions").value(true))
                .andExpect(jsonPath("$[0].fields[1].autoSelectSingle").value(true))
                .andExpect(jsonPath("$[0].tools[0].name").value("write_note"))
                .andExpect(jsonPath("$[0].tools[0].title").value("메모 쓰기"))
                .andExpect(jsonPath("$[0].tools[0].risk").value("WRITE"))
                .andExpect(jsonPath("$[0].tools[0].approval").value("REQUIRED"))
                .andExpect(jsonPath("$[0].tools[0].grant").value(true))
                .andExpect(jsonPath("$[0].fields[0].env").doesNotExist())
                .andExpect(jsonPath("$[0].fields[1].options").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("DEMO_TOKEN");
    }

    @Test
    @DisplayName("카탈로그에서 빠진 내 연결은 available 거짓과 빈 칸으로 나간다")
    void catalogMarksRemovedConnectorAsUnavailableWithEmptyFields() throws Exception {
        when(service.catalog(MEMBER))
                .thenReturn(List.of(
                        new ConnectorSummary(DEMO, "검사용 메모", "", List.of(), List.of(), ConnectionStatus.READY, false)));

        mvc.perform(get("/api/v1/connectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DEMO))
                .andExpect(jsonPath("$[0].title").value("검사용 메모"))
                .andExpect(jsonPath("$[0].description").value(""))
                .andExpect(jsonPath("$[0].myStatus").value("READY"))
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].fields").isEmpty())
                .andExpect(jsonPath("$[0].tools").isEmpty());
    }

    @Test
    @DisplayName("등록 본문의 profile은 무시하고 로그인 사용자로 등록하며 응답에 비밀 원문이 없다")
    void registersForLoggedInUserIgnoringProfileInBody() throws Exception {
        when(service.register(any(), any(), any())).thenReturn(snapshot(ConnectionStatus.PENDING));

        MvcResult result = mvc.perform(post("/api/v1/connections/" + DEMO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"token\":\"" + TOKEN + "\"},\"profile\":\"other-profile\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectorId").value(DEMO))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.secretPrefixes.token").value("demo"))
                .andExpect(jsonPath("$.values.scope").value("a"))
                .andExpect(jsonPath("$.agentCode").value("agent-code"))
                .andExpect(jsonPath("$.restartRequired").value(false))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN);
        verify(service).register(MEMBER, DEMO, Map.of("token", TOKEN));
    }

    @Test
    @DisplayName("값 요청의 문자열 표현과 잘못된 JSON은 원문을 노출하지 않는다")
    void hidesValuesInToStringAndMalformedJson() throws Exception {
        assertThat(new ConnectionDtos.ValuesRequest(Map.of("token", TOKEN)).toString())
                .doesNotContain(TOKEN);
        MvcResult result = mvc.perform(post("/api/v1/connections/" + DEMO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"token\":[\"" + TOKEN + "\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("인증되지 않은 연결 조회는 401을 돌려준다")
    void returns401ForUnauthenticatedConnectionLookup() throws Exception {
        when(currentUser.require()).thenThrow(new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));

        mvc.perform(get("/api/v1/connections/" + DEMO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("연결 조회는 경로의 커넥터를 로그인 사용자의 것으로만 읽는다")
    void readsConnectionOnlyForLoggedInUser() throws Exception {
        when(service.read(MEMBER, DEMO)).thenReturn(snapshot(ConnectionStatus.READY));

        mvc.perform(get("/api/v1/connections/" + DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        verify(service).read(MEMBER, DEMO);
    }

    @Test
    @DisplayName("선택지는 로그인 사용자의 작성 중인 값으로 읽고 값을 응답하지 않는다")
    void readsOptionsWithCandidateValuesWithoutEchoingThem() throws Exception {
        when(service.options(MEMBER, DEMO, "scope", Map.of("token", TOKEN)))
                .thenReturn(List.of(new ConnectorOption("a", "범위 A")));

        MvcResult result = mvc.perform(post("/api/v1/connections/" + DEMO + "/options/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("a"))
                .andExpect(jsonPath("$[0].label").value("범위 A"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("확인과 해제는 요청 본문 없이 로그인 사용자로 수행한다")
    void checksAndDisconnectsForLoggedInUserWithoutRequestBody() throws Exception {
        when(service.check(MEMBER, DEMO)).thenReturn(snapshot(ConnectionStatus.PENDING));
        when(service.disconnect(MEMBER, DEMO))
                .thenReturn(new ConnectionSnapshot(
                        DEMO, ConnectionStatus.DISCONNECTED, Map.of(), Map.of(), true, null, "agent-code", 0));

        mvc.perform(post("/api/v1/connections/" + DEMO + "/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.undeclaredTools").value(2));
        mvc.perform(delete("/api/v1/connections/" + DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCONNECTED"))
                .andExpect(jsonPath("$.restartRequired").value(true))
                .andExpect(jsonPath("$.secretPrefixes").isEmpty());

        verify(service).check(MEMBER, DEMO);
        verify(service).disconnect(MEMBER, DEMO);
    }

    @Test
    @DisplayName("커넥터 오류 코드는 정해진 HTTP 상태로 나간다")
    void connectorErrorCodesMapToDocumentedStatuses() throws Exception {
        expectRegisterFailure(new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "no such connector"), 404);
        expectRegisterFailure(new ApiException(ErrorCode.CONNECTOR_CREDENTIAL_REJECTED, "rejected"), 400);
        expectRegisterFailure(new ApiException(ErrorCode.CONNECTOR_FORBIDDEN, "forbidden"), 403);
        expectRegisterFailure(new ApiException(ErrorCode.CONNECTOR_UNAVAILABLE, "unavailable"), 503);
        expectRegisterFailure(new ApiException(ErrorCode.VALIDATION_FAILED, "invalid"), 400);
        expectRegisterFailure(new ConnectorOperationFailure(), 502);
    }

    @Test
    @DisplayName("호출 제한에 걸린 선택지 조회는 429 와 CONNECTOR_RATE_LIMITED 를 돌려준다")
    void returns429WhenOptionsCallIsRateLimited() throws Exception {
        when(service.options(any(), any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.CONNECTOR_RATE_LIMITED, "too many connector calls"));

        MvcResult result = mvc.perform(post("/api/v1/connections/" + DEMO + "/options/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CONNECTOR_RATE_LIMITED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("MEMBER는 관리자 목록을 읽지 못한다")
    void forbidsMemberFromReadingAdminList() throws Exception {
        when(currentUser.requireAdmin())
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "administrator access is required"));

        mvc.perform(get("/api/v1/admin/connections"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.name()));

        verify(service, never()).listForAdmin(any());
    }

    @Test
    @DisplayName("관리자 목록은 다른 사용자의 비밀 앞부분과 칸 값을 내보내지 않는다")
    void adminListHidesOtherUsersPrefixesAndValues() throws Exception {
        when(service.listForAdmin(ADMIN))
                .thenReturn(List.of(
                        new AdminConnectionSnapshot(DEMO, 7L, "사용자", ConnectionStatus.PENDING, "agent-code", true, 3)));

        mvc.perform(get("/api/v1/admin/connections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].connectorId").value(DEMO))
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[0].displayName").value("사용자"))
                .andExpect(jsonPath("$[0].restartRequired").value(true))
                .andExpect(jsonPath("$[0].undeclaredTools").value(3))
                .andExpect(jsonPath("$[0].secretPrefixes").doesNotExist())
                .andExpect(jsonPath("$[0].values").doesNotExist());
    }

    @Test
    @DisplayName("관리자 반영 확인 응답은 비밀 앞부분과 칸 값을 내보내지 않는다")
    void adminConfirmResponseHidesPrefixesAndValues() throws Exception {
        when(service.confirmApplied(ADMIN, DEMO, 7L)).thenReturn(snapshot(ConnectionStatus.READY));

        mvc.perform(post("/api/v1/admin/connections/" + DEMO + "/7/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectorId").value(DEMO))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.secretPrefixes").doesNotExist())
                .andExpect(jsonPath("$.values").doesNotExist());
    }

    private void expectRegisterFailure(ApiException failure, int status) throws Exception {
        doThrow(failure).when(service).register(any(), any(), any());

        MvcResult result = mvc.perform(post("/api/v1/connections/" + DEMO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(failure.code().name()))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TOKEN);
    }

    private static ConnectionSnapshot snapshot(ConnectionStatus status) {
        return new ConnectionSnapshot(
                DEMO, status, Map.of("token", "demo"), Map.of("scope", "a"), false, null, "agent-code", 2);
    }
}
