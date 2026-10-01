package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpHermesConnectorClientTest {
    private static final String BASE = "https://dashboard.example.test";
    private static final String DEMO = "demo-notes";
    private static final String PROFILE = "user-demo";
    private static final String TOKEN = "demo_ok_0123456789";
    private static final String CATALOG = """
            [{"id":"demo-notes","title":"검사용 메모","description":"검사에서만 쓰는 커넥터입니다.",
              "fields":[
                {"key":"token","env":"DEMO_TOKEN","label":"토큰","description":"검사용 토큰입니다.",
                 "secret":true,"required":true,"pattern":"^demo_[a-z]+_[0-9]{10}$"},
                {"key":"scope","env":"DEMO_SCOPE","label":"범위","required":false,
                 "options":{"tool":"list_scopes","items":"scopes","value":"id","label":"name",
                            "auto_select_single":true}}],
              "verify":{"tool":"list_scopes"},"mcp_server":"demo"}]
            """;
    private HttpHermesConnectorClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpHermesConnectorClient(
                new HermesProperties("unused", BASE, "test-dashboard-token", BASE, null, null, null, null));
        ReflectionTestUtils.setField(client, "client", builder.build());
    }

    @DisplayName("카탈로그에서 칸의 env, 선택지, 확인 도구, MCP 서버 이름을 읽는다")
    @Test
    void readsCatalogWithEnvOptionsVerifyToolAndMcpServer() {
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-dashboard-token"))
                .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        List<ConnectorManifest> catalog = client.readCatalog();

        assertThat(catalog)
                .containsExactly(new ConnectorManifest(
                        DEMO,
                        "검사용 메모",
                        "검사에서만 쓰는 커넥터입니다.",
                        List.of(
                                new ConnectorField(
                                        "token",
                                        "DEMO_TOKEN",
                                        "토큰",
                                        "검사용 토큰입니다.",
                                        true,
                                        true,
                                        "^demo_[a-z]+_[0-9]{10}$",
                                        null),
                                new ConnectorField(
                                        "scope",
                                        "DEMO_SCOPE",
                                        "범위",
                                        "",
                                        false,
                                        false,
                                        null,
                                        new ConnectorFieldOptions("list_scopes", "scopes", "id", "name", true))),
                        "list_scopes",
                        "demo"));
        server.verify();
    }

    @DisplayName("커넥터가 하나도 없는 카탈로그는 빈 목록이다")
    @Test
    void readsEmptyCatalog() {
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.readCatalog()).isEmpty();
        server.verify();
    }

    @DisplayName("모양이 틀린 카탈로그를 거절한다")
    @Test
    void rejectsMalformedCatalog() {
        String url = BASE + "/api/connectors/catalog";
        server.expect(requestTo(url)).andRespond(withSuccess("{\"connectors\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        CATALOG.replace("\"mcp_server\":\"demo\"", "\"mcp_server\":7"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(CATALOG.replace("\"env\":\"DEMO_TOKEN\",", ""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        CATALOG.replace("\"verify\":{\"tool\":\"list_scopes\"},", ""), MediaType.APPLICATION_JSON));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> client.readCatalog()).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }

    @DisplayName("도구 호출은 커넥터 경로에 도구와 값을 보내고 성공 결과를 돌려준다")
    @Test
    void callSendsToolAndValuesAndReturnsResult() {
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/call"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-dashboard-token"))
                .andExpect(content().json("{\"tool\":\"list_scopes\",\"values\":{\"token\":\"" + TOKEN + "\"}}"))
                .andRespond(withSuccess(
                        "{\"ok\":true,\"result\":{\"scopes\":[{\"id\":\"a\",\"name\":\"범위 A\"}]}}",
                        MediaType.APPLICATION_JSON));

        CallResult result = client.call(DEMO, "list_scopes", Map.of("token", TOKEN));

        assertThat(result.ok()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.result().get("scopes").get(0).get("id").asString()).isEqualTo("a");
        server.verify();
    }

    @DisplayName("도구 호출의 실패는 공통 어휘 하나로 읽는다")
    @Test
    void callReadsFailureAsCommonErrorWord() {
        String url = BASE + "/api/connectors/demo-notes/call";
        for (String word : List.of("credential_rejected", "forbidden", "invalid_input", "unavailable")) {
            server.expect(requestTo(url))
                    .andRespond(withSuccess("{\"ok\":false,\"error\":\"" + word + "\"}", MediaType.APPLICATION_JSON));
        }

        assertThat(List.of(
                        client.call(DEMO, "list_scopes", Map.of()),
                        client.call(DEMO, "list_scopes", Map.of()),
                        client.call(DEMO, "list_scopes", Map.of()),
                        client.call(DEMO, "list_scopes", Map.of())))
                .extracting(CallResult::error)
                .containsExactly(
                        ConnectorCallError.CREDENTIAL_REJECTED,
                        ConnectorCallError.FORBIDDEN,
                        ConnectorCallError.INVALID_INPUT,
                        ConnectorCallError.UNAVAILABLE);
        server.verify();
    }

    @DisplayName("도구 호출의 모양이 틀린 응답과 거절 응답은 값을 싣지 않은 예외로 끝난다")
    @Test
    void callRejectsMalformedAndRefusedResponsesWithoutLeakingValues() {
        String url = BASE + "/api/connectors/demo-notes/call";
        server.expect(requestTo(url)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"ok\":false,\"error\":\"something_else\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"result\":{\"scopes\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(TOKEN).contentType(MediaType.TEXT_PLAIN));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> client.call(DEMO, "list_scopes", Map.of("token", TOKEN)))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(error -> {
                        assertThat(error.getCause()).isNull();
                        assertThat(error.toString()).doesNotContain(TOKEN);
                    });
        }
        server.verify();
    }

    @DisplayName("이름을 받은 커넥터만 켜고 그 커넥터의 설정된 상태를 읽는다")
    @Test
    void installsOnlyTheNamedConnectorAndReadsConfiguredState() {
        server.expect(requestTo(BASE + "/api/connectors"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer test-dashboard-token"))
                .andExpect(content().json("{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true}"))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,\"restart_required\":true}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors?profile=user-demo"))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"connectors\":["
                                + "{\"plugin\":\"other\",\"enabled\":false,\"configured\":false},"
                                + "{\"plugin\":\"demo-notes\",\"enabled\":true,\"configured\":true}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.putConnector(PROFILE, DEMO, true)).isTrue();
        var state = client.readConnector(PROFILE, DEMO);
        assertThat(state.enabled()).isTrue();
        assertThat(state.configured()).isTrue();
        server.verify();
    }

    @DisplayName("설치 응답이 다른 커넥터나 다른 enabled 를 말하면 거절한다")
    @Test
    void rejectsInstallResponseForAnotherConnectorOrState() {
        server.expect(requestTo(BASE + "/api/connectors"))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"plugin\":\"other\",\"enabled\":true,\"restart_required\":false}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":false,\"restart_required\":false}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.putConnector(PROFILE, DEMO, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.putConnector(PROFILE, DEMO, true)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @DisplayName("환경 변수 쓰기와 지우기는 profile 범위의 JSON 으로 보내고 재시작 필요 여부를 유지한다")
    @Test
    void envWritesAndDeletesUseProfileScopedJsonAndPreserveRestart() {
        server.expect(requestTo(BASE + "/api/env"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(
                        content().json("{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\",\"value\":\"test-secret\"}"))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\",\"restart_required\":true}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/env"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\"}"))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\",\"restart_required\":true}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.putEnv(PROFILE, "DEMO_TOKEN", "test-secret")).isTrue();
        assertThat(client.deleteEnv(PROFILE, "DEMO_TOKEN")).isTrue();
        server.verify();
    }

    @DisplayName("없는 환경 변수를 지우면 오류 본문이 있어도 실패하지 않고 지운 것이 없다고 답한다")
    @Test
    void missingEnvIsIdempotentEvenWithAnErrorBody() {
        server.expect(requestTo(BASE + "/api/env"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("missing key").contentType(MediaType.TEXT_PLAIN));
        assertThat(client.deleteEnv(PROFILE, "DEMO_SCOPE")).isFalse();
        server.verify();
    }

    @DisplayName("모양이 맞는 목록에 받은 커넥터가 없으면 설치되지 않은 것으로 읽는다")
    @Test
    void readsConnectorMissingFromWellFormedListAsNotInstalled() {
        String url = BASE + "/api/connectors?profile=user-demo";
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"profile\":\"user-demo\",\"connectors\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"connectors\":["
                                + "{\"plugin\":\"other\",\"enabled\":true,\"configured\":true}]}",
                        MediaType.APPLICATION_JSON));

        for (int attempt = 0; attempt < 2; attempt++) {
            var state = client.readConnector(PROFILE, DEMO);
            assertThat(state.profile()).isEqualTo(PROFILE);
            assertThat(state.enabled()).isFalse();
            assertThat(state.configured()).isFalse();
        }
        server.verify();
    }

    @DisplayName("설치 목록의 모양이 틀리면 설치되지 않은 것으로 읽지 않고 거절한다")
    @Test
    void rejectsMalformedConnectorListInsteadOfReadingAsNotInstalled() {
        String url = BASE + "/api/connectors?profile=user-demo";
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"profile\":\"user-demo\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"profile\":\"user-demo\",\"connectors\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(
                        withSuccess("{\"profile\":\"another-user\",\"connectors\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess(
                        "{\"profile\":\"user-demo\",\"connectors\":[{\"plugin\":\"demo-notes\",\"enabled\":true}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> client.readConnector(PROFILE, DEMO)).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }

    @DisplayName("본문이 빈 성공 응답과 다른 profile 의 응답을 거절한다")
    @Test
    void rejectsEmptySuccessAndWrongProfileResponses() {
        server.expect(requestTo(BASE + "/api/env")).andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/env"))
                .andRespond(withSuccess(
                        "{\"profile\":\"another-user\",\"key\":\"DEMO_TOKEN\",\"restart_required\":false}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.deleteEnv(PROFILE, "DEMO_TOKEN")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.putEnv(PROFILE, "DEMO_TOKEN", "test-secret"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @DisplayName("받은 이름의 서버를 점검하고 도구 이름이 잘못되면 거절한다")
    @Test
    void probesGivenServerAndRejectsMalformedToolNames() {
        String url = BASE + "/api/mcp/servers/demo/test?profile=user-demo";
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"ok\":true,\"tools\":[{\"name\":\"list_scopes\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andRespond(withSuccess("{\"ok\":true,\"tools\":[{\"name\":\"\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.probe(PROFILE, "demo").tools()).containsExactly("list_scopes");
        assertThatThrownBy(() -> client.probe(PROFILE, "demo")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @DisplayName("원격이 돌려준 비밀값을 예외와 원인에서 버린다")
    @Test
    void discardsRemoteSecretFromExceptionAndCause() {
        server.expect(requestTo(BASE + "/api/env"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("test-secret-echo")
                        .contentType(MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.putEnv(PROFILE, "DEMO_TOKEN", "test-secret-echo"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.toString()).doesNotContain("test-secret-echo");
                });
        server.verify();
    }
}
