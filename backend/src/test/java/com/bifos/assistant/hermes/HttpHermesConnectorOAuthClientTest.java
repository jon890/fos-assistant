package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Authorization;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Credentials;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Exchange;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Mode;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.port.ConnectorOAuthFlowConflict;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HttpHermesConnectorOAuthClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

class HttpHermesConnectorOAuthClientTest {
    private static final String BASE = "https://dashboard.example.test";
    private static final String DEMO = "demo-notes";
    private static final String VAULT = "c1";
    private static final String NONCE = "nonce-from-begin";
    private static final String REDIRECT = "https://assistant.example/connections/oauth/callback";
    private static final String PENDING_URL = "https://assistant.example/internal/connector-token/p1.nonce-from-begin.signature";
    private static final String CURRENT_URL = "https://assistant.example/internal/connector-token/k1.signature";
    private static final Credentials APP = new Credentials("Iv1.example", "client-secret-example");
    private static final String APP_BODY = """
        {"vault":"c1","connector":"demo-notes","client_id":"Iv1.example","client_secret":"client-secret-example"}
        """;
    private static final Path FIXTURES = Path.of("../hermes/tests/fixtures/connector-oauth");
    private HttpHermesConnectorOAuthClient client;
    private MockRestServiceServer server;


    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpHermesConnectorOAuthClient(
            new HermesProperties("unused", BASE + "/", "test-dashboard-token", BASE, null, null, null, null));
        ReflectionTestUtils.setField(client, "client", builder.build());
    }


    @Test
    @DisplayName("교환과 갱신은 Hermes 공유 예시의 키와 만료 시각을 그대로 사용한다")
    void consumesSharedExchangeAndRefreshFixtures() throws IOException {
        expect("exchange", fixture("exchange-request.json"))
            .andRespond(withSuccess(fixture("exchange-response.json"), MediaType.APPLICATION_JSON));
        expect("refresh", APP_BODY)
            .andRespond(withSuccess(fixture("refresh-response.json"), MediaType.APPLICATION_JSON));
        expect("refresh", APP_BODY)
            .andRespond(withSuccess(fixture("refresh-missing-response.json"), MediaType.APPLICATION_JSON));
        Token expected = new Token(Outcome.OK, "access-token-example", Instant.ofEpochSecond(1795000000));

        assertThat(client.exchange(new Exchange(VAULT, DEMO, NONCE, APP,
            "code-from-redirect", "pkce-verifier-example", REDIRECT, PENDING_URL))).isEqualTo(expected);
        assertThat(client.refresh(VAULT, DEMO, APP)).isEqualTo(expected);
        assertThat(client.refresh(VAULT, DEMO, APP)).isEqualTo(new Token(Outcome.MISSING, null, null));
        assertThat(expected.toString()).doesNotContain(expected.accessToken());
        server.verify();
    }


    @Test
    @DisplayName("대기 파일과 로그인 주소를 만들고 commit 에 본 연결 주소를 넣는다")
    void startsAndCommitsUsingTheDocumentedBodies() {
        expect("begin", """
            {"vault":"c1","connector":"demo-notes","values":{"scope":"example"}}
            """).andRespond(withSuccess("{\"nonce\":\"nonce-from-begin\"}", MediaType.APPLICATION_JSON));
        expect("authorize-url", """
            {"connector":"demo-notes","mode":"install","client_id":"Iv1.example","app_slug":"example-app",
            "redirect_uri":"%s","state":"state-example"}
            """.formatted(REDIRECT)).andRespond(withSuccess("{\"url\":\"https://accounts.example.test/install\"}", MediaType.APPLICATION_JSON));
        expect("commit", """
            {"vault":"c1","nonce":"nonce-from-begin","token_url":"%s"}
            """.formatted(CURRENT_URL)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.begin(VAULT, DEMO, Map.of("scope", "example"))).isEqualTo(NONCE);
        assertThat(client.authorizeUrl(new Authorization(DEMO, Mode.INSTALL, APP.clientId(),
            "example-app", REDIRECT, "state-example", null))).isEqualTo("https://accounts.example.test/install");
        client.commit(VAULT, NONCE, CURRENT_URL);
        server.verify();
    }


    @Test
    @DisplayName("대기 정리와 허락 취소는 토큰 유무에 맞게 보내고 주소 교체는 앱 자격을 보내지 않는다")
    void discardsRevokesAndReplacesUrlWithSeparateBodies() {
        expect("discard", """
            {"vault":"c1","connector":"demo-notes","nonce":"nonce-from-begin",
            "client_id":"Iv1.example","client_secret":"client-secret-example","access_token":"access-token-example"}
            """).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        expect("revoke", APP_BODY)
            .andRespond(withSuccess("{\"outcome\":\"grant_revoked\"}", MediaType.APPLICATION_JSON));
        expect("token-url", """
            {"vault":"c1","token_url":"%s"}
            """.formatted(CURRENT_URL)).andRespond(withSuccess("{\"outcome\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.discard(VAULT, DEMO, NONCE, APP, "access-token-example");
        assertThat(client.revoke(VAULT, DEMO, APP, null)).isEqualTo(Outcome.GRANT_REVOKED);
        assertThat(client.replaceTokenUrl(VAULT, CURRENT_URL)).isEqualTo(Outcome.OK);
        server.verify();
    }


    @Test
    @DisplayName("5xx 응답이나 토큰 칸이 어긋난 응답은 일시 장애로 처리한다")
    void distinguishesTransportFailureFromProviderOutcome() {
        expect("refresh", APP_BODY).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON).body("{\"outcome\":\"grant_revoked\",\"detail\":\"client-secret-example\"}"));
        expect("refresh", APP_BODY).andRespond(withSuccess("{\"outcome\":\"unknown\"}", MediaType.APPLICATION_JSON));
        expect("refresh", APP_BODY)
            .andRespond(withSuccess("{\"outcome\":\"ok\",\"access_token\":\"test\",\"expires_at\":\"bad\"}", MediaType.APPLICATION_JSON));
        expect("refresh", APP_BODY).andRespond(withSuccess("{\"outcome\":\"app_rejected\"}", MediaType.APPLICATION_JSON));
        expect("refresh", APP_BODY)
            .andRespond(withSuccess("{\"outcome\":\"ok\",\"access_token\":\"example\",\"expires_at\":null}", MediaType.APPLICATION_JSON));

        for (int index = 0; index < 3; index++) {
            assertThat(client.refresh(VAULT, DEMO, APP)).isEqualTo(new Token(Outcome.UNAVAILABLE, null, null));
        }
        assertThat(client.refresh(VAULT, DEMO, APP)).isEqualTo(new Token(Outcome.APP_REJECTED, null, null));
        assertThat(client.refresh(VAULT, DEMO, APP)).isEqualTo(new Token(Outcome.OK, "example", null));
        server.verify();
    }


    @Test
    @DisplayName("대기 흐름 충돌은 구분하고 원격 오류의 비밀값은 예외에 남기지 않는다")
    void reportsConflictWithoutRemoteBody() {
        String body = """
            {"vault":"c1","nonce":"nonce-from-begin","token_url":"%s"}
            """.formatted(CURRENT_URL);
        expect("commit", body).andRespond(withStatus(HttpStatus.CONFLICT).body("secret-from-upstream"));
        expect("commit", body).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("secret-from-upstream"));

        assertThatThrownBy(() -> client.commit(VAULT, NONCE, CURRENT_URL))
            .isInstanceOf(ConnectorOAuthFlowConflict.class).hasNoCause();
        assertThatThrownBy(() -> client.commit(VAULT, NONCE, CURRENT_URL))
            .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining("secret-from-upstream");
        server.verify();
    }


    private ResponseActions expect(String action, String body) {
        return server.expect(requestTo(BASE + "/api/connector-oauth/" + action))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-dashboard-token"))
            .andExpect(content().json(body, JsonCompareMode.STRICT));
    }


    private static String fixture(String file) throws IOException {
        return Files.readString(FIXTURES.resolve(file));
    }
}
